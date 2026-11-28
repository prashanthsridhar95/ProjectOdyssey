// Edited at deploy time. Everything else is compiled into customer.js.
// The API is served on this same address (nginx proxies /v1), so the browser reuses one connection and skips CORS pre-checks.
window.ODYSSEY = { apiUrl: location.origin, tenant: "carwash" };
