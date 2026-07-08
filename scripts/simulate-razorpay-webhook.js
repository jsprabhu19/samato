#!/usr/bin/env node
/**
 * In-process Razorpay webhook simulator.
 *
 *   - Reads RAZORPAY_WEBHOOK_SECRET from infra/.env
 *   - Builds a real Razorpay-shaped payload
 *   - Computes the X-Razorpay-Signature (HMAC-SHA256 of body)
 *   - POSTs to http://localhost:8084/api/payments/webhooks/razorpay
 *
 * Usage:
 *   node scripts/simulate-razorpay-webhook.js <event-type> <razorpay-order-id> <razorpay-payment-id>
 *
 * Examples:
 *   node scripts/simulate-razorpay-webhook.js payment.captured order_XXX pay_YYY
 *   node scripts/simulate-razorpay-webhook.js payment.failed   order_XXX pay_YYY
 *   node scripts/simulate-razorpay-webhook.js refund.processed "" pay_YYY
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const http = require('http');

// Load infra/.env manually
const envFile = path.join(__dirname, '..', 'infra', '.env');
const env = {};
fs.readFileSync(envFile, 'utf8')
  .split('\n')
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith('#') && l.includes('='))
  .forEach((l) => {
    const [k, ...rest] = l.split('=');
    env[k.trim()] = rest.join('=').trim();
  });

const SECRET = env.RAZORPAY_WEBHOOK_SECRET;
if (!SECRET) {
  console.error('RAZORPAY_WEBHOOK_SECRET not found in infra/.env');
  process.exit(1);
}

const [, , eventType, razorpayOrderId, razorpayPaymentId, amountPaiseStr] = process.argv;
if (!eventType || !razorpayPaymentId) {
  console.error(
    'Usage: node simulate-razorpay-webhook.js <event-type> <razorpay-order-id> <razorpay-payment-id> [amount-paise]'
  );
  process.exit(1);
}

const amountPaise = parseInt(amountPaiseStr || '50000', 10); // 50000 paise = INR 500

function buildPayload(type, orderId, payId, amt) {
  const eventId = `evt_${crypto.randomBytes(8).toString('hex')}`;
  const createdAt = Math.floor(Date.now() / 1000);
  if (type === 'payment.captured') {
    return {
      entity: 'event',
      account_id: 'acc_TEST',
      event: 'payment.captured',
      contains: ['payment'],
      payload: {
        payment: {
          entity: {
            id: payId,
            entity: 'payment',
            amount: amt,
            currency: 'INR',
            status: 'captured',
            order_id: orderId,
            invoice_id: null,
            international: false,
            method: 'card',
            amount_refunded: 0,
            refund_status: null,
            captured: true,
            description: 'Order payment',
            card_id: 'card_TEST',
            error_code: null,
            error_description: null,
            email: 'alice@example.com',
            contact: '+919999999999',
            fee: 0,
            tax: 0,
            created_at: createdAt,
          },
        },
      },
      created_at: createdAt,
      id: eventId,
    };
  }
  if (type === 'payment.failed') {
    return {
      entity: 'event',
      account_id: 'acc_TEST',
      event: 'payment.failed',
      contains: ['payment'],
      payload: {
        payment: {
          entity: {
            id: payId,
            entity: 'payment',
            amount: amt,
            currency: 'INR',
            status: 'failed',
            order_id: orderId,
            error_code: 'BAD_REQUEST_ERROR',
            error_description: 'Payment failed (simulated)',
            email: 'alice@example.com',
            contact: '+919999999999',
            created_at: createdAt,
          },
        },
      },
      created_at: createdAt,
      id: eventId,
    };
  }
  if (type === 'refund.processed') {
    return {
      entity: 'event',
      account_id: 'acc_TEST',
      event: 'refund.processed',
      contains: ['refund'],
      payload: {
        refund: {
          entity: {
            id: `rfnd_${crypto.randomBytes(6).toString('hex')}`,
            entity: 'refund',
            amount: amt,
            currency: 'INR',
            payment_id: payId,
            status: 'processed',
            created_at: createdAt,
          },
        },
      },
      created_at: createdAt,
      id: eventId,
    };
  }
  throw new Error(`Unknown event type: ${type}`);
}

const payload = buildPayload(eventType, razorpayOrderId, razorpayPaymentId, amountPaise);
const body = JSON.stringify(payload);
const signature = crypto.createHmac('sha256', SECRET).update(body).digest('hex');

console.log(`→ POST /api/payments/webhooks/razorpay`);
console.log(`   event:     ${eventType}`);
console.log(`   event_id:  ${payload.id}`);
console.log(`   order_id:  ${razorpayOrderId || '(none)'}`);
console.log(`   payment_id:${razorpayPaymentId}`);
console.log(`   amount:    ${amountPaise} paise`);
console.log(`   sig:       ${signature.substring(0, 16)}...`);

const req = http.request(
  {
    host: 'localhost',
    port: 8084,
    path: '/api/payments/webhooks/razorpay',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
      'X-Razorpay-Signature': signature,
    },
  },
  (res) => {
    let chunks = '';
    res.on('data', (c) => (chunks += c));
    res.on('end', () => {
      console.log(`← HTTP ${res.statusCode} ${chunks || '(empty body)'}`);
      process.exit(res.statusCode >= 200 && res.statusCode < 300 ? 0 : 1);
    });
  }
);
req.on('error', (e) => {
  console.error('Request failed:', e.message);
  process.exit(1);
});
req.write(body);
req.end();
