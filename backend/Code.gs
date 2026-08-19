/**
 * AutoDialer License Backend (Google Apps Script)
 * -------------------------------------------------
 * Deploy this as a Web App (free, no hosting cost) attached to a Google Sheet.
 * See backend/SETUP.md for step-by-step deployment instructions.
 *
 * Google Sheet needs 2 tabs:
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
 *   Phone | PinHash | ActiveDeviceId | UpdatedAt
 *   (filled automatically by the script - don't edit rows manually)
 */

function doGet(e) {
  var action = e.parameter.action;
  if (action === 'redeem') return handleRedeem(e);
  if (action === 'check') return handleCheck(e);
  if (action === 'verifyPayment') return handleVerifyPayment(e);
  if (action === 'login') return handleLogin(e);
  if (action === 'resetPin') return handleResetPin(e);
  if (action === 'checkSession') return handleCheckSession(e);
  return jsonResponse({ status: 'error', message: 'unknown action' });
}

function getSheet(name) {
  return SpreadsheetApp.getActiveSpreadsheet().getSheetByName(name);
}

/**
 * Verifies a Razorpay payment directly with Razorpay's servers (server-side,
 * so the secret key never touches the Android app) and grants access if the
 * payment is real, captured, and the amount matches the plan.
 */
function handleVerifyPayment(e) {
  var paymentId = String(e.parameter.paymentId || '').trim();
  var deviceId = String(e.parameter.deviceId || '').trim();
  var planType = String(e.parameter.planType || '').trim().toUpperCase();

  if (!paymentId || !deviceId || !planType) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  // Idempotency: never grant the same payment twice (e.g. if the app retries).
  var actSheet = getSheet('Activations');
  var existing = actSheet.getDataRange().getValues();
  for (var i = 1; i < existing.length; i++) {
    if (String(existing[i][1]) === paymentId) {
      return jsonResponse({ status: 'ok', expiryAt: Number(existing[i][3]), planType: existing[i][4] });
    }
  }

  var props = PropertiesService.getScriptProperties();
  var keyId = props.getProperty('RAZORPAY_KEY_ID');
  var keySecret = props.getProperty('RAZORPAY_KEY_SECRET');
  if (!keyId || !keySecret) {
    return jsonResponse({ status: 'error', message: 'Razorpay keys backend me set nahi hain' });
  }

  var expectedAmountPaise = { 'HOURLY12': 1000, 'MONTHLY': 30000, 'YEARLY': 100000 }[planType];
  if (!expectedAmountPaise) {
    return jsonResponse({ status: 'error', message: 'Invalid plan type' });
  }

  var authHeader = 'Basic ' + Utilities.base64Encode(keyId + ':' + keySecret);
  var response;
  try {
    response = UrlFetchApp.fetch('https://api.razorpay.com/v1/payments/' + paymentId, {
      headers: { Authorization: authHeader },
      muteHttpExceptions: true
    });
  } catch (err) {
    return jsonResponse({ status: 'error', message: 'Razorpay se contact nahi ho paya' });
  }

  var payment = JSON.parse(response.getContentText());
  if (payment.error) {
    return jsonResponse({ status: 'error', message: 'Payment verify nahi hua: ' + payment.error.description });
  }
  if (payment.status !== 'captured') {
    return jsonResponse({ status: 'error', message: 'Payment abhi captured nahi hua (status: ' + payment.status + ')' });
  }
  if (Number(payment.amount) !== expectedAmountPaise) {
    return jsonResponse({ status: 'error', message: 'Payment amount plan se match nahi karta' });
  }

  var now = Date.now();
  var expiryAt = grantExpiry(planType, now);

  actSheet.appendRow([deviceId, paymentId, now, expiryAt, planType, false]);

  return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: planType });
}

function grantExpiry(type, now) {
  if (type === 'FREE') return now + (100 * 365 * 24 * 60 * 60 * 1000);
  if (type === 'YEARLY') return now + (365 * 24 * 60 * 60 * 1000);
  if (type === 'HOURLY12') return now + (12 * 60 * 60 * 1000);
  return now + (30 * 24 * 60 * 60 * 1000); // MONTHLY
}

function handleRedeem(e) {
  var code = String(e.parameter.code || '').trim().toUpperCase();
  var deviceId = String(e.parameter.deviceId || '').trim();
  if (!code || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

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
    actSheet.appendRow([deviceId, code, now, expiryAt, type, false]);

    return jsonResponse({ status: 'ok', expiryAt: expiryAt, planType: type });
  }

  return jsonResponse({ status: 'error', message: 'Invalid code' });
}

function handleCheck(e) {
  var deviceId = String(e.parameter.deviceId || '').trim();
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
 */

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

function handleLogin(e) {
  var phone = String(e.parameter.phone || '').trim();
  var pin = String(e.parameter.pin || '').trim();
  var deviceId = String(e.parameter.deviceId || '').trim();

  if (!phone || !pin || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }
  if (pin.length !== 4 || !/^[0-9]{4}$/.test(pin)) {
    return jsonResponse({ status: 'error', message: 'PIN must be 4 digits' });
  }

  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, phone);
  var pinHash = hashPin(pin);
  var now = Date.now();

  if (rowNum === -1) {
    // First time this number has ever logged in - this PIN becomes their PIN.
    sheet.appendRow([phone, pinHash, deviceId, now]);
    return jsonResponse({ status: 'ok', registered: true });
  }

  var row = sheet.getRange(rowNum, 1, 1, 4).getValues()[0];
  if (String(row[1]) !== pinHash) {
    return jsonResponse({ status: 'error', message: 'Wrong PIN' });
  }

  // Correct PIN - this device becomes the one and only active device for this number.
  sheet.getRange(rowNum, 3).setValue(deviceId);
  sheet.getRange(rowNum, 4).setValue(now);
  return jsonResponse({ status: 'ok', registered: false });
}

function handleResetPin(e) {
  var phone = String(e.parameter.phone || '').trim();
  var newPin = String(e.parameter.newPin || '').trim();
  var deviceId = String(e.parameter.deviceId || '').trim();

  if (!phone || !newPin || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }
  if (newPin.length !== 4 || !/^[0-9]{4}$/.test(newPin)) {
    return jsonResponse({ status: 'error', message: 'PIN must be 4 digits' });
  }

  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, phone);
  var pinHash = hashPin(newPin);
  var now = Date.now();

  if (rowNum === -1) {
    sheet.appendRow([phone, pinHash, deviceId, now]);
  } else {
    sheet.getRange(rowNum, 2).setValue(pinHash);
    sheet.getRange(rowNum, 3).setValue(deviceId);
    sheet.getRange(rowNum, 4).setValue(now);
  }
  return jsonResponse({ status: 'ok' });
}

function handleCheckSession(e) {
  var phone = String(e.parameter.phone || '').trim();
  var deviceId = String(e.parameter.deviceId || '').trim();
  if (!phone || !deviceId) {
    return jsonResponse({ status: 'error', message: 'missing params' });
  }

  var sheet = getUsersSheet();
  var rowNum = findUserRow(sheet, phone);
  if (rowNum === -1) return jsonResponse({ status: 'not_found' });

  var activeDeviceId = sheet.getRange(rowNum, 3).getValue();
  return jsonResponse({ status: 'ok', active: String(activeDeviceId) === deviceId });
}
