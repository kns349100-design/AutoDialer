/**
 * AutoDialer License Backend (Google Apps Script)
 * -------------------------------------------------
 * Deploy this as a Web App (free, no hosting cost) attached to a Google Sheet.
 * See backend/SETUP.md for step-by-step deployment instructions.
 *
 * Google Sheet needs 3 tabs:
 *
 * Tab "Codes"  (row 1 = headers)
 *   Code | Type | MaxUses | UsedCount | Active
 *   e.g.  SAPDEAL-FREE | FREE     | 0  | 0 | TRUE
 *         SAPDEAL-150  | MONTHLY  | 0  | 0 | TRUE
 *         SAPDEAL-100  | MONTHLY  | 0  | 0 | TRUE
 *         SAPDEAL-10   | HOURLY12 | 0  | 0 | TRUE
 *   MaxUses = 0 means unlimited uses for that code.
 *   Type options: FREE, HOURLY12 (12 hours), MONTHLY (30 days), YEARLY (365 days)
 *
 * Tab "Activations" (row 1 = headers)
 *   DeviceId | Code | ActivatedAt | ExpiryAt | PlanType | Revoked
 *   (this tab is filled automatically by the script - don't edit rows,
 *    except to set Revoked = TRUE on a row to cut off one device/branch)
 *
 * Tab "Users" (row 1 = headers) - for phone number + PIN login
 *   Phone | PinHash | ActiveDeviceId | UpdatedAt | FailedAttempts | LockedUntil
 *   (filled automatically by the script - don't edit rows manually)
 *
 * ---- Reliability notes for anyone maintaining this file ----
 * - Every request that reads-then-writes a sheet takes a script lock first
 *   (see withLock()), so two requests arriving at the same instant can never
 *   both redeem the last use of a code, both log in with a wrong PIN guess
 *   without it counting, etc.
 * - doGet() never lets an exception escape as a raw Apps Script error page -
 *   it always returns clean JSON, because the app can only parse JSON.
 * - Every value that gets written into a Sheet cell is passed through
 *   safeCell() first, so a device ID or phone number that happens to start
 *   with "=", "+", "-" or "@" can never be misread by Sheets as a formula.
 */

function doGet(e) {
  try {
    var action = e && e.parameter ? e.parameter.action : null;
    if (action === 'redeem') return handleRedeem(e);
    if (action === 'check') return handleCheck(e);
    if (action === 'createPaymentLink') return handleCreatePaymentLink(e);
    if (action === 'checkPayment') return handleCheckPayment(e);
    if (action === 'payPage') return handlePayPage(e);
    if (action === 'login') return handleLogin(e);
    if (action === 'resetPin') return handleResetPin(e);
    if (action === 'checkSession') return handleCheckSession(e);
    return jsonResponse({ status: 'error', message: 'unknown action' });
  } catch (err) {
    // Never let a raw exception escape - the app can only understand JSON.
    return jsonResponse({ status: 'error', message: 'Server error, please try again' });
  }
}

function getSheet(name) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(name);
  if (!sheet) throw new Error('Missing sheet tab: ' + name);
  return sheet;
}

/**
 * Takes a script-wide lock for the duration of fn(), waiting up to 10s for
 * any other in-flight request to finish first. Wrap this around ANY
 * read-then-write sequence on a sheet (redeem a code, log in, grant a
 * payment) so two simultaneous requests can never race each other into an
 * inconsistent state (e.g. a 1-use code being redeemed twice at once).
 */
function withLock(fn) {
  var lock = LockService.getScriptLock();
  var gotLock = lock.tryLock(10000);
  if (!gotLock) {
    return jsonResponse({ status: 'error', message: 'Server is busy, try again in a moment' });
  }
  try {
    return fn();
  } finally {
    lock.releaseLock();
  }
}

/** Prevents any value written into a Sheet cell from being misread as a
 * formula - if it starts with =, +, -, or @, prefix it with an apostrophe
 * (Sheets' own "treat as text" escape) so it is always stored literally. */
function safeCell(value) {
  var s = String(value);
  if (/^[=+\-@]/.test(s)) return "'" + s;
  return s;
}

/** Caps + trims any free-text input coming from the app before it's used
 * anywhere, so an unexpectedly huge or malformed value can't cause slow
 * sheet operations or bad rows. */
function cleanInput(value, maxLen) {
  var s = String(value == null ? '' : value).trim();
  if (s.length > maxLen) s = s.substring(0, maxLen);
  return s;
}

var PLAN_AMOUNTS = { HOURLY12: 10, MONTHLY: 300, YEARLY: 1000 };

/**
 * Cashfree base API URL - sandbox for TEST keys, live for PROD keys. Controlled by the
 * CASHFREE_ENV script property ('TEST' or 'PROD', defaults to TEST so nothing breaks if
 * it's not set yet).
 */
function cashfreeBaseUrl() {
  var env = PropertiesService.getScriptProperties().getProperty('CASHFREE_ENV');
  return (env === 'PROD') ? 'https://api.cashfree.com' : 'https://sandbox.cashfree.com';
}

function cashfreeHeaders() {
  var props = PropertiesService.getScriptProperties();
  return {
    'x-client-id': props.getProperty('CASHFREE_APP_ID'),
    'x-client-secret': props.getProperty('CASHFREE_SECRET_KEY'),
    'x-api-version': '2023-08-01',
    'Content-Type': 'application/json'
  };
}

/**
 * Creates a Cashfree Order and returns a URL (pointing back at this same Apps Script Web
 * App) that shows a tiny auto-redirecting page using Cashfree's official JS SDK - this is
 * Cashfree's "Standard Checkout", the one flow every account gets by default with zero extra
 * approval (unlike Payment Links and Custom/Headless Checkout, both of which returned "not
 * enabled or approved" errors on this account). Order creation itself was already confirmed
 * working earlier, so this routes entirely through calls that are proven to work.
 */
function handleCreatePaymentLink(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var planType = cleanInput(e.parameter.planType, 20).toUpperCase();
  var phone = cleanInput(e.parameter.phone, 20);

  if (!deviceId || !planType) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  var amountRupees = PLAN_AMOUNTS[planType];
  if (!amountRupees) {
    return jsonResponse({ status: 'error', message: 'Invalid plan type' });
  }

  var props = PropertiesService.getScriptProperties();
  if (!props.getProperty('CASHFREE_APP_ID') || !props.getProperty('CASHFREE_SECRET_KEY')) {
    return jsonResponse({ status: 'error', message: 'Cashfree keys backend me set nahi hain' });
  }

  if (!/^[0-9]{10}$/.test(phone)) phone = '9999999999'; // Cashfree requires a 10-digit phone

  var orderId = 'AD' + planType + deviceId.replace(/[^a-zA-Z0-9]/g, '').slice(-8) + Date.now();
  var customerId = ('cust' + deviceId.replace(/[^a-zA-Z0-9]/g, '')).slice(0, 40);

  var orderPayload = {
    order_id: orderId,
    order_amount: amountRupees,
    order_currency: 'INR',
    customer_details: {
      customer_id: customerId,
      customer_phone: phone,
      customer_name: 'AutoDialer User'
    }
  };

  var orderResponse;
  try {
    orderResponse = UrlFetchApp.fetch(cashfreeBaseUrl() + '/pg/orders', {
      method: 'post',
      headers: cashfreeHeaders(),
      payload: JSON.stringify(orderPayload),
      muteHttpExceptions: true
    });
  } catch (err) {
    return jsonResponse({ status: 'error', message: 'Cashfree se contact nahi ho paya' });
  }

  var order;
  try {
    order = JSON.parse(orderResponse.getContentText());
  } catch (err) {
    return jsonResponse({ status: 'error', message: 'Cashfree se galat response mila' });
  }

  if (!order.payment_session_id) {
    return jsonResponse({ status: 'error', message: 'Order nahi ban paya: ' + (order.message || JSON.stringify(order)) });
  }

  var isProd = PropertiesService.getScriptProperties().getProperty('CASHFREE_ENV') === 'PROD';
  var payUrl = ScriptApp.getService().getUrl() +
    '?action=payPage&session=' + encodeURIComponent(order.payment_session_id) +
    '&mode=' + (isProd ? 'production' : 'sandbox');

  return jsonResponse({ status: 'ok', linkUrl: payUrl, linkId: orderId, planType: planType });
}

/**
 * Serves a tiny self-contained page that loads Cashfree's own official JS SDK and hands off
 * to their Standard Checkout hosted page - this is the normal, always-available way to accept
 * a payment on any Cashfree account (no special product approval needed), unlike the Payment
 * Links / Custom Checkout APIs that returned "not enabled or approved" on this account.
 */
function handlePayPage(e) {
  var session = String(e.parameter.session || '');
  var mode = (String(e.parameter.mode || '') === 'production') ? 'production' : 'sandbox';

  var html =
    '<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#0D0E1A;color:#F6F7FC;}</style>' +
    '</head><body>' +
    '<div>Opening secure payment page…</div>' +
    '<script src="https://sdk.cashfree.com/js/v3/cashfree.js"></script>' +
    '<script>' +
    'var cashfree = Cashfree({ mode: "' + mode + '" });' +
    'cashfree.checkout({ paymentSessionId: "' + session + '", redirectTarget: "_self" });' +
    '</script>' +
    '</body></html>';

  return HtmlService.createHtmlOutput(html)
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

/**
 * Checks a Cashfree Order's status directly with Cashfree's servers and grants access if
 * it's really been paid and the amount matches the plan. Locked so two near-simultaneous
 * status checks for the same order can never both append an Activations row.
 */
function handleCheckPayment(e) {
  var orderId = cleanInput(e.parameter.linkId, 100);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  var planType = cleanInput(e.parameter.planType, 20).toUpperCase();

  if (!orderId || !deviceId || !planType) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  var expectedAmountRupees = PLAN_AMOUNTS[planType];
  if (!expectedAmountRupees) {
    return jsonResponse({ status: 'error', message: 'Invalid plan type' });
  }

  return withLock(function () {
    // Idempotency: never grant the same payment twice (e.g. if the app retries).
    var actSheet = getSheet('Activations');
    var existing = actSheet.getDataRange().getValues();
    for (var i = 1; i < existing.length; i++) {
      if (String(existing[i][1]) === orderId) {
        return jsonResponse({ status: 'ok', expiryAt: Number(existing[i][3]), planType: existing[i][4] });
      }
    }

    var response;
    try {
      response = UrlFetchApp.fetch(cashfreeBaseUrl() + '/pg/orders/' + encodeURIComponent(orderId), {
        headers: cashfreeHeaders(),
        muteHttpExceptions: true
      });
    } catch (err) {
      return jsonResponse({ status: 'error', message: 'Cashfree se contact nahi ho paya' });
    }

    var order;
    try {
      order = JSON.parse(response.getContentText());
    } catch (err) {
      return jsonResponse({ status: 'error', message: 'Cashfree se galat response mila' });
    }

    if (order.order_status !== 'PAID') {
      return jsonResponse({ status: 'error', message: 'Payment abhi complete nahi hua (status: ' + (order.order_status || 'unknown') + ')' });
    }
    // Small tolerance for float rounding (e.g. Cashfree returning 9.999999 for 10).
    if (Number(order.order_amount) < expectedAmountRupees - 0.5) {
      return jsonResponse({ status: 'error', message: 'Payment amount plan se match nahi karta' });
    }

    var now = Date.now();
    var expiryAt = grantExpiry(planType, now);

    actSheet.appendRow([safeCell(deviceId), safeCell(orderId), now, expiryAt, planType, false]);

    return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: planType });
  });
}


function grantExpiry(type, now) {
  if (type === 'FREE') return now + (100 * 365 * 24 * 60 * 60 * 1000);
  if (type === 'YEARLY') return now + (365 * 24 * 60 * 60 * 1000);
  if (type === 'HOURLY12') return now + (12 * 60 * 60 * 1000);
  return now + (30 * 24 * 60 * 60 * 1000); // MONTHLY
}

/** Locked so two devices redeeming the same limited-use code at the exact same
 * instant can never both succeed and push its use count past the limit. */
function handleRedeem(e) {
  var code = cleanInput(e.parameter.code, 50).toUpperCase();
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!code || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  return withLock(function () {
    var codesSheet = getSheet('Codes');
    var data = codesSheet.getDataRange().getValues();

    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      var rowCode = String(row[0]).trim().toUpperCase();
      if (rowCode !== code) continue;

      var type = String(row[1]).trim().toUpperCase();
      var maxUses = Number(row[2]) || 0;
      var usedCount = Number(row[3]) || 0;
      var active = row[4];

      if (active !== true && String(active).toUpperCase() !== 'TRUE') {
        return jsonResponse({ status: 'error', message: 'Ye code disabled hai' });
      }
      if (maxUses > 0 && usedCount >= maxUses) {
        return jsonResponse({ status: 'error', message: 'Is code ki limit khatam ho gayi' });
      }

      var now = Date.now();
      var expiryAt = grantExpiry(type, now);

      codesSheet.getRange(i + 1, 4).setValue(usedCount + 1);

      var actSheet = getSheet('Activations');
      actSheet.appendRow([safeCell(deviceId), safeCell(code), now, expiryAt, type, false]);

      return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: type });
    }

    return jsonResponse({ status: 'error', message: 'Invalid code' });
  });
}

function handleCheck(e) {
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!deviceId) return jsonResponse({ status: 'error', message: 'missing deviceId' });

  var actSheet = getSheet('Activations');
  var data = actSheet.getDataRange().getValues();

  var latestExpiry = 0;
  var latestType = '';

  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    if (String(row[0]).trim() !== deviceId) continue;
    var revoked = row[5];
    if (revoked === true || String(revoked).toUpperCase() === 'TRUE') continue; // skip revoked rows
    var expiryAt = Number(row[3]) || 0;
    if (expiryAt > latestExpiry) {
      latestExpiry = expiryAt;
      latestType = row[4];
    }
  }

  if (latestExpiry === 0) return jsonResponse({ status: 'not_found' });
  return jsonResponse({ status: 'ok', expiryAt: latestExpiry, planType: latestType });
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * ---- Phone number + PIN login (free, no SMS, no billing) ----
 * First login for a phone number registers that PIN. After that, the same PIN
 * must be entered. Logging in successfully on a new device automatically makes
 * that the only active device for the number (older device gets signed out).
 *
 * Brute-force protection: after 5 wrong PINs in a row for a phone number, that
 * number is locked out for 15 minutes - makes guessing a 4-digit PIN (10,000
 * combinations) impractical instead of trivial.
 */

var MAX_FAILED_ATTEMPTS = 5;
var LOCKOUT_MS = 15 * 60 * 1000;

function hashPin(pin) {
  var raw = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, pin);
  var hex = raw.map(function (b) {
    var v = (b < 0) ? b + 256 : b;
    var h = v.toString(16);
    return h.length === 1 ? '0' + h : h;
  }).join('');
  return hex;
}

function getUsersSheet() {
  return getSheet('Users');
}

function findUserRow(sheet, phone) {
  var data = sheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    if (String(data[i][0]).trim() === phone) return i + 1; // 1-indexed sheet row
  }
  return -1;
}

/** Locked end-to-end so two login attempts for the same phone at the same
 * instant can never both register a different PIN as "the" PIN, and so a
 * failed-attempt counter can never be double-counted or skipped. */
function handleLogin(e) {
  var phone = cleanInput(e.parameter.phone, 20);
  var pin = cleanInput(e.parameter.pin, 10);
  var deviceId = cleanInput(e.parameter.deviceId, 200);

  if (!phone || !pin || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }
  if (pin.length !== 4 || !/^[0-9]{4}$/.test(pin)) {
    return jsonResponse({ status: 'error', message: 'PIN must be 4 digits' });
  }

  return withLock(function () {
    var sheet = getUsersSheet();
    var rowNum = findUserRow(sheet, phone);
    var pinHash = hashPin(pin);
    var now = Date.now();

    if (rowNum === -1) {
      // First time this number has ever logged in - this PIN becomes their PIN.
      sheet.appendRow([safeCell(phone), pinHash, safeCell(deviceId), now, 0, 0]);
      return jsonResponse({ status: 'ok', registered: true });
    }

    // getRange width 6 to also read FailedAttempts/LockedUntil (older sheets
    // without these two columns simply read as blank/0, which is safe).
    var row = sheet.getRange(rowNum, 1, 1, 6).getValues()[0];
    var lockedUntil = Number(row[5]) || 0;
    if (lockedUntil > now) {
      var minutesLeft = Math.ceil((lockedUntil - now) / 60000);
      return jsonResponse({ status: 'error', message: 'Too many wrong PIN attempts. Try again in ' + minutesLeft + ' min.' });
    }

    if (String(row[1]) !== pinHash) {
      var failedAttempts = (Number(row[4]) || 0) + 1;
      var lockValue = 0;
      if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
        lockValue = now + LOCKOUT_MS;
        failedAttempts = 0;
      }
      sheet.getRange(rowNum, 5, 1, 2).setValues([[failedAttempts, lockValue]]);
      if (lockValue > 0) {
        return jsonResponse({ status: 'error', message: 'Too many wrong PIN attempts. Try again in 15 min.' });
      }
      return jsonResponse({ status: 'error', message: 'Wrong PIN' });
    }

    // Correct PIN - this device becomes the one and only active device for this number.
    sheet.getRange(rowNum, 3, 1, 4).setValues([[safeCell(deviceId), now, 0, 0]]);
    return jsonResponse({ status: 'ok', registered: false });
  });
}

function handleResetPin(e) {
  var phone = cleanInput(e.parameter.phone, 20);
  var newPin = cleanInput(e.parameter.newPin, 10);
  var deviceId = cleanInput(e.parameter.deviceId, 200);

  if (!phone || !newPin || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }
  if (newPin.length !== 4 || !/^[0-9]{4}$/.test(newPin)) {
    return jsonResponse({ status: 'error', message: 'PIN must be 4 digits' });
  }

  return withLock(function () {
    var sheet = getUsersSheet();
    var rowNum = findUserRow(sheet, phone);
    var pinHash = hashPin(newPin);
    var now = Date.now();

    if (rowNum === -1) {
      sheet.appendRow([safeCell(phone), pinHash, safeCell(deviceId), now, 0, 0]);
    } else {
      sheet.getRange(rowNum, 2, 1, 5).setValues([[pinHash, safeCell(deviceId), now, 0, 0]]);
    }
    return jsonResponse({ status: 'ok' });
  });
}

function handleCheckSession(e) {
  var phone = cleanInput(e.parameter.phone, 20);
  var deviceId = cleanInput(e.parameter.deviceId, 200);
  if (!phone || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, phone);
  if (rowNum === -1) return jsonResponse({ status: 'not_found' });

  var activeDeviceId = sheet.getRange(rowNum, 3).getValue();
  return jsonResponse({ status: 'ok', active: String(activeDeviceId) === deviceId });
}
