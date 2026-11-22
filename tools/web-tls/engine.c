/* Each instance has its own TLS context and bounded I/O buffers. The only host
 * capability the module imports is cryptographically secure random bytes. */
#include <stdint.h>
#include <string.h>
#include "mbedtls/ssl.h"
#include "mbedtls/platform_util.h"
#include "psa/crypto.h"
#include "psa/crypto_extra.h"

#define CAP 24576
static unsigned char scratch[CAP], net_input[CAP], net_output[CAP];
static size_t in_offset, in_size, out_size;
static mbedtls_ssl_config config;
static mbedtls_ssl_context ssl;
static const uint16_t groups[] = { MBEDTLS_SSL_IANA_TLS_GROUP_X25519, 0 };

__attribute__((import_module("browser"), import_name("random")))
extern void secure_random(unsigned char *bytes, size_t length);

psa_status_t mbedtls_psa_external_get_random(mbedtls_psa_external_random_context_t *ctx,
    uint8_t *out, size_t size, size_t *written) {
    (void) ctx;
    secure_random(out, size);
    *written = size;
    return PSA_SUCCESS;
}

static int rng(void *ctx, unsigned char *buf, size_t size) {
    (void) ctx;
    secure_random(buf, size);
    return 0;
}

static int transmit(void *ctx, const unsigned char *buf, size_t size) {
    (void) ctx;
    if (size > CAP - out_size) return MBEDTLS_ERR_SSL_BUFFER_TOO_SMALL;
    memcpy(net_output + out_size, buf, size);
    out_size += size;
    return (int) size;
}

static int consume(void *ctx, unsigned char *buf, size_t size) {
    (void) ctx;
    if (in_offset == in_size) return MBEDTLS_ERR_SSL_WANT_READ;
    size_t count = in_size - in_offset;
    if (count > size) count = size;
    memcpy(buf, net_input + in_offset, count);
    in_offset += count;
    return (int) count;
}

unsigned char *wc_buffer(void) { return scratch; }

int wc_start(int host, size_t identity_size) {
    if (identity_size == 0 || identity_size > 128) return -1;
    if (psa_crypto_init() != PSA_SUCCESS) return -1;
    mbedtls_ssl_init(&ssl);
    mbedtls_ssl_config_init(&config);
    int rc = mbedtls_ssl_config_defaults(&config, host ? MBEDTLS_SSL_IS_SERVER : MBEDTLS_SSL_IS_CLIENT,
        MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT);
    if (rc != 0) return rc;
    mbedtls_ssl_conf_groups(&config, groups);
    mbedtls_ssl_conf_tls13_key_exchange_modes(&config, MBEDTLS_SSL_TLS1_3_KEY_EXCHANGE_MODE_PSK_EPHEMERAL);
    mbedtls_ssl_conf_rng(&config, rng, NULL);
    rc = mbedtls_ssl_conf_psk(&config, scratch, 32, scratch + 32, identity_size);
    mbedtls_platform_zeroize(scratch, 32);
    if (rc != 0) return rc;
    rc = mbedtls_ssl_setup(&ssl, &config);
    if (rc != 0) return rc;
    mbedtls_ssl_set_bio(&ssl, NULL, transmit, consume, NULL);
    return 0;
}

int wc_feed(size_t length) {
    if (length > CAP || in_offset != in_size) return -1;
    memcpy(net_input, scratch, length);
    in_offset = 0;
    in_size = length;
    return 0;
}

int wc_ready(void) { return mbedtls_ssl_is_handshake_over(&ssl); }
int wc_handshake(void) {
    int rc = mbedtls_ssl_handshake(&ssl);
    return rc == MBEDTLS_ERR_SSL_WANT_READ ? 0 : rc;
}

int wc_read(void) {
    int rc = mbedtls_ssl_read(&ssl, scratch, CAP);
    if (rc == MBEDTLS_ERR_SSL_WANT_READ) return 0;
    return rc == 0 ? MBEDTLS_ERR_SSL_CONN_EOF : rc;
}

int wc_write(size_t size) {
    if (size > 8002) return -1;
    int rc = mbedtls_ssl_write(&ssl, scratch, size);
    mbedtls_platform_zeroize(scratch, size);
    return rc == (int)size ? 0 : -1;
}

size_t wc_output(void) {
    size_t count = out_size;
    memcpy(scratch, net_output, count);
    out_size = 0;
    return count;
}

void wc_free(void) {
    mbedtls_ssl_free(&ssl);
    mbedtls_ssl_config_free(&config);
    mbedtls_psa_crypto_free();
}
