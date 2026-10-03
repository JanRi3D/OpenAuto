/*
 * JNI shim that runs TLS 1.2 through the device's own OpenSSL (libssl.so / libcrypto.so), for
 * me.ri3d.openauto.aa.NativeTls. Nothing is linked at build time: the libraries are opened with
 * dlopen and the few entry points below are resolved by name, so the app carries no crypto code of
 * its own and falls back to its Java engine when anything is missing.
 *
 * Only used on Android 4.1 - 5.1, where the system library is OpenSSL 1.0.1 and apps may open it.
 * The constants are the OpenSSL 1.0.x values (ssl.h, bio.h); several "functions" of that API are
 * macros over SSL_CTX_ctrl / BIO_ctrl and are spelled out here.
 *
 * TLS records travel inside protocol frames, so the SSL object talks to two memory BIOs instead of
 * a socket: bytes from the phone are written into rbio, bytes for the phone are read from wbio.
 */
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SSL_ERROR_WANT_READ 2
#define SSL_ERROR_WANT_WRITE 3
#define SSL_ERROR_ZERO_RETURN 6
#define SSL_CTRL_SET_TMP_ECDH 4
#define SSL_CTRL_OPTIONS 32
#define SSL_OP_NO_TICKET 0x00004000L
#define SSL_OP_NO_COMPRESSION 0x00020000L
#define BIO_CTRL_PENDING 10
#define BIO_C_SET_BUF_MEM_EOF_RETURN 130
#define NID_X9_62_prime256v1 415

/* Forward secrecy with AES-GCM first (what a real phone chose), older suites as a fallback. */
#define CIPHERS "ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384:AES128-GCM-SHA256:" \
                "ECDHE-RSA-AES128-SHA256:ECDHE-RSA-AES128-SHA:AES128-SHA"

static struct {
    int (*SSL_library_init)(void);
    const void *(*TLSv1_2_client_method)(void);
    const void *(*TLSv1_2_server_method)(void);
    void *(*SSL_CTX_new)(const void *method);
    void (*SSL_CTX_free)(void *ctx);
    long (*SSL_CTX_ctrl)(void *ctx, int cmd, long larg, void *parg);
    int (*SSL_CTX_set_cipher_list)(void *ctx, const char *list);
    int (*SSL_CTX_use_certificate_ASN1)(void *ctx, int len, const unsigned char *der);
    int (*SSL_CTX_use_PrivateKey)(void *ctx, void *pkey);
    void *(*SSL_new)(void *ctx);
    void (*SSL_free)(void *ssl);
    void (*SSL_set_bio)(void *ssl, void *rbio, void *wbio);
    void (*SSL_set_connect_state)(void *ssl);
    void (*SSL_set_accept_state)(void *ssl);
    int (*SSL_do_handshake)(void *ssl);
    int (*SSL_read)(void *ssl, void *buf, int num);
    int (*SSL_write)(void *ssl, const void *buf, int num);
    int (*SSL_get_error)(const void *ssl, int ret);
    const void *(*SSL_get_current_cipher)(const void *ssl);
    const char *(*SSL_CIPHER_get_name)(const void *cipher);
    const char *(*SSL_get_version)(const void *ssl);
    void *(*BIO_new)(void *method);
    void *(*BIO_s_mem)(void);
    int (*BIO_read)(void *bio, void *buf, int len);
    int (*BIO_write)(void *bio, const void *buf, int len);
    long (*BIO_ctrl)(void *bio, int cmd, long larg, void *parg);
    void *(*d2i_AutoPrivateKey)(void **out, const unsigned char **in, long len);
    void (*EVP_PKEY_free)(void *pkey);
    void *(*EC_KEY_new_by_curve_name)(int nid);
    void (*EC_KEY_free)(void *key);
    unsigned long (*ERR_get_error)(void);
    void (*ERR_error_string_n)(unsigned long e, char *buf, size_t len);
    void (*ERR_clear_error)(void);
    const char *(*SSLeay_version)(int type);
} o;

static int loaded; /* 0 not tried, 1 usable, -1 unusable */
static char load_error[96] = "not loaded";

typedef struct {
    void *ctx, *ssl, *rbio, *wbio;
    char error[160];
} Engine;

#define SYM(lib, name)                                                          \
    do {                                                                        \
        *(void **) &o.name = dlsym(lib, #name);                                 \
        if (!o.name) {                                                          \
            snprintf(load_error, sizeof load_error, "%s has no %s", #lib, #name); \
            return 0;                                                           \
        }                                                                       \
    } while (0)

static int load(void) {
    if (loaded) return loaded > 0;
    loaded = -1;
    void *libcrypto = dlopen("libcrypto.so", RTLD_NOW);
    void *libssl = dlopen("libssl.so", RTLD_NOW);
    if (!libcrypto || !libssl) {
        snprintf(load_error, sizeof load_error, "system OpenSSL cannot be opened");
        return 0;
    }
    SYM(libssl, SSL_library_init);
    SYM(libssl, TLSv1_2_client_method);
    SYM(libssl, TLSv1_2_server_method);
    SYM(libssl, SSL_CTX_new);
    SYM(libssl, SSL_CTX_free);
    SYM(libssl, SSL_CTX_ctrl);
    SYM(libssl, SSL_CTX_set_cipher_list);
    SYM(libssl, SSL_CTX_use_certificate_ASN1);
    SYM(libssl, SSL_CTX_use_PrivateKey);
    SYM(libssl, SSL_new);
    SYM(libssl, SSL_free);
    SYM(libssl, SSL_set_bio);
    SYM(libssl, SSL_set_connect_state);
    SYM(libssl, SSL_set_accept_state);
    SYM(libssl, SSL_do_handshake);
    SYM(libssl, SSL_read);
    SYM(libssl, SSL_write);
    SYM(libssl, SSL_get_error);
    SYM(libssl, SSL_get_current_cipher);
    SYM(libssl, SSL_CIPHER_get_name);
    SYM(libssl, SSL_get_version);
    SYM(libcrypto, BIO_new);
    SYM(libcrypto, BIO_s_mem);
    SYM(libcrypto, BIO_read);
    SYM(libcrypto, BIO_write);
    SYM(libcrypto, BIO_ctrl);
    SYM(libcrypto, d2i_AutoPrivateKey);
    SYM(libcrypto, EVP_PKEY_free);
    SYM(libcrypto, EC_KEY_new_by_curve_name);
    SYM(libcrypto, EC_KEY_free);
    SYM(libcrypto, ERR_get_error);
    SYM(libcrypto, ERR_error_string_n);
    SYM(libcrypto, ERR_clear_error);
    SYM(libcrypto, SSLeay_version);
    o.SSL_library_init();
    loaded = 1;
    return 1;
}

/* Remembers the first queued OpenSSL error (or the given fallback text) for NativeTls.error(). */
static void fail(Engine *e, const char *what) {
    unsigned long code = o.ERR_get_error();
    if (code) {
        char text[120];
        o.ERR_error_string_n(code, text, sizeof text);
        snprintf(e->error, sizeof e->error, "%s: %s", what, text);
    } else {
        snprintf(e->error, sizeof e->error, "%s", what);
    }
    o.ERR_clear_error();
}

static void destroy(Engine *e) {
    if (e->ssl) o.SSL_free(e->ssl); /* frees both BIOs as well */
    if (e->ctx) o.SSL_CTX_free(e->ctx);
    free(e);
}

JNIEXPORT jstring JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nLibrary(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, load() ? o.SSLeay_version(0) : load_error);
}

JNIEXPORT jlong JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nCreate(JNIEnv *env, jclass cls, jbyteArray cert, jbyteArray key, jboolean server) {
    if (!load()) return 0;
    Engine *e = calloc(1, sizeof *e);
    if (!e) return 0;
    o.ERR_clear_error();
    e->ctx = o.SSL_CTX_new(server ? o.TLSv1_2_server_method() : o.TLSv1_2_client_method());
    if (!e->ctx) goto broken;
    o.SSL_CTX_ctrl(e->ctx, SSL_CTRL_OPTIONS, SSL_OP_NO_COMPRESSION | SSL_OP_NO_TICKET, NULL);
    if (o.SSL_CTX_set_cipher_list(e->ctx, CIPHERS) != 1) goto broken;

    jsize certLen = (*env)->GetArrayLength(env, cert), keyLen = (*env)->GetArrayLength(env, key);
    jbyte *certBytes = (*env)->GetByteArrayElements(env, cert, NULL);
    jbyte *keyBytes = (*env)->GetByteArrayElements(env, key, NULL);
    int ok = 0;
    if (certBytes && keyBytes) {
        const unsigned char *p = (const unsigned char *) keyBytes;
        void *pkey = o.d2i_AutoPrivateKey(NULL, &p, keyLen); /* PKCS#8 or traditional */
        ok = pkey && o.SSL_CTX_use_certificate_ASN1(e->ctx, certLen, (const unsigned char *) certBytes) == 1
             && o.SSL_CTX_use_PrivateKey(e->ctx, pkey) == 1;
        if (pkey) o.EVP_PKEY_free(pkey);
    }
    if (certBytes) (*env)->ReleaseByteArrayElements(env, cert, certBytes, JNI_ABORT);
    if (keyBytes) (*env)->ReleaseByteArrayElements(env, key, keyBytes, JNI_ABORT);
    if (!ok) goto broken;

    if (server) { /* only the self-test plays the phone's role; ECDHE needs a curve on that side */
        void *curve = o.EC_KEY_new_by_curve_name(NID_X9_62_prime256v1);
        if (!curve) goto broken;
        o.SSL_CTX_ctrl(e->ctx, SSL_CTRL_SET_TMP_ECDH, 0, curve);
        o.EC_KEY_free(curve);
    }
    e->ssl = o.SSL_new(e->ctx);
    e->rbio = o.BIO_new(o.BIO_s_mem());
    e->wbio = o.BIO_new(o.BIO_s_mem());
    if (!e->ssl || !e->rbio || !e->wbio) goto broken;
    /* an empty memory BIO must mean "try again later", not end of stream */
    o.BIO_ctrl(e->rbio, BIO_C_SET_BUF_MEM_EOF_RETURN, -1, NULL);
    o.BIO_ctrl(e->wbio, BIO_C_SET_BUF_MEM_EOF_RETURN, -1, NULL);
    o.SSL_set_bio(e->ssl, e->rbio, e->wbio);
    if (server) o.SSL_set_accept_state(e->ssl); else o.SSL_set_connect_state(e->ssl);
    return (jlong) (intptr_t) e;

broken:
    o.ERR_clear_error();
    destroy(e);
    return 0;
}

JNIEXPORT void JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nFree(JNIEnv *env, jclass cls, jlong handle) {
    if (handle) destroy((Engine *) (intptr_t) handle);
}

JNIEXPORT jstring JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nError(JNIEnv *env, jclass cls, jlong handle) {
    return (*env)->NewStringUTF(env, ((Engine *) (intptr_t) handle)->error);
}

JNIEXPORT jstring JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nCipher(JNIEnv *env, jclass cls, jlong handle) {
    Engine *e = (Engine *) (intptr_t) handle;
    const void *cipher = o.SSL_get_current_cipher(e->ssl);
    char text[96];
    snprintf(text, sizeof text, "%s %s", o.SSL_get_version(e->ssl), cipher ? o.SSL_CIPHER_get_name(cipher) : "?");
    return (*env)->NewStringUTF(env, text);
}

/*
 * One handshake step: feeds the peer's flight (may be empty), then writes our next flight to out.
 * Returns the number of bytes written to out; done[0] is set once the handshake has completed.
 * -1 on failure (see nError).
 */
JNIEXPORT jint JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nHandshake(JNIEnv *env, jclass cls, jlong handle, jbyteArray in, jint inOff, jint inLen,
                                              jbyteArray out, jbooleanArray done) {
    Engine *e = (Engine *) (intptr_t) handle;
    o.ERR_clear_error();
    if (inLen > 0) {
        jbyte *p = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
        if (!p) return -1;
        o.BIO_write(e->rbio, p + inOff, inLen);
        (*env)->ReleasePrimitiveArrayCritical(env, in, p, JNI_ABORT);
    }
    int r = o.SSL_do_handshake(e->ssl);
    jboolean finished = r == 1;
    if (!finished) {
        int err = o.SSL_get_error(e->ssl, r);
        if (err != SSL_ERROR_WANT_READ && err != SSL_ERROR_WANT_WRITE) {
            fail(e, "TLS handshake failed");
            return -1;
        }
    }
    jint cap = (*env)->GetArrayLength(env, out), n = 0;
    if (o.BIO_ctrl(e->wbio, BIO_CTRL_PENDING, 0, NULL) > cap) {
        fail(e, "handshake flight does not fit the buffer");
        return -1;
    }
    jbyte *q = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!q) return -1;
    n = o.BIO_read(e->wbio, q, cap);
    (*env)->ReleasePrimitiveArrayCritical(env, out, q, 0);
    (*env)->SetBooleanArrayRegion(env, done, 0, 1, &finished);
    return n < 0 ? 0 : n;
}

/* Encrypts one plaintext chunk into TLS record(s) at out[outOff..]; returns their length, -1 on failure. */
JNIEXPORT jint JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nSeal(JNIEnv *env, jclass cls, jlong handle, jbyteArray in, jint inOff, jint inLen,
                                         jbyteArray out, jint outOff) {
    Engine *e = (Engine *) (intptr_t) handle;
    jint cap = (*env)->GetArrayLength(env, out) - outOff, n = -1;
    o.ERR_clear_error();
    jbyte *p = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
    if (!p) return -1;
    int w = o.SSL_write(e->ssl, p + inOff, inLen);
    (*env)->ReleasePrimitiveArrayCritical(env, in, p, JNI_ABORT);
    if (w != inLen) {
        fail(e, "TLS write failed");
        return -1;
    }
    if (o.BIO_ctrl(e->wbio, BIO_CTRL_PENDING, 0, NULL) > cap) {
        fail(e, "ciphertext does not fit frame buffer");
        return -1;
    }
    jbyte *q = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!q) return -1;
    n = o.BIO_read(e->wbio, q + outOff, cap);
    (*env)->ReleasePrimitiveArrayCritical(env, out, q, 0);
    return n;
}

/*
 * Decrypts the TLS record(s) in in[inOff..] to out[outOff..]. Returns the plaintext length (0 when
 * the record is not complete yet), -1 on failure, -2 when the peer closed the TLS connection.
 */
JNIEXPORT jint JNICALL
Java_me_ri3d_openauto_aa_NativeTls_nOpen(JNIEnv *env, jclass cls, jlong handle, jbyteArray in, jint inOff, jint inLen,
                                         jbyteArray out, jint outOff) {
    Engine *e = (Engine *) (intptr_t) handle;
    jint cap = (*env)->GetArrayLength(env, out) - outOff, total = 0, result = 0;
    o.ERR_clear_error();
    jbyte *p = (*env)->GetPrimitiveArrayCritical(env, in, NULL);
    if (!p) return -1;
    o.BIO_write(e->rbio, p + inOff, inLen);
    (*env)->ReleasePrimitiveArrayCritical(env, in, p, JNI_ABORT);

    jbyte *q = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!q) return -1;
    for (;;) {
        if (total == cap) { /* a full buffer may hide more plaintext: check without consuming it */
            char probe;
            int r = o.SSL_read(e->ssl, &probe, 1);
            if (r > 0) {
                fail(e, "plaintext does not fit message buffer");
                result = -1;
            } else if (o.SSL_get_error(e->ssl, r) == SSL_ERROR_ZERO_RETURN) {
                result = -2;
            }
            break;
        }
        int r = o.SSL_read(e->ssl, q + outOff + total, cap - total);
        if (r > 0) {
            total += r;
            continue;
        }
        int err = o.SSL_get_error(e->ssl, r);
        if (err == SSL_ERROR_ZERO_RETURN) {
            result = -2;
        } else if (err != SSL_ERROR_WANT_READ) {
            fail(e, "TLS read failed");
            result = -1;
        }
        break;
    }
    (*env)->ReleasePrimitiveArrayCritical(env, out, q, 0);
    return result < 0 ? result : total;
}
