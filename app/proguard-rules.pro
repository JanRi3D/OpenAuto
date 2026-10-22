# Android Auto uses lightweight TLS. CarPlay Lockdown uses explicitly scoped BC/BCJSSE providers.
# BC registers these TLS primitives by constructed class names. R8 cannot infer those reflective
# constructors: without this, a shrunk APK builds but SSLContext.init fails on API 16 (SHA-512).
# Retain the selected TLS families, rather than the entire provider and its unrelated algorithms.
-keep,allowoptimization class org.bouncycastle.jcajce.provider.digest.MD5* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.digest.SHA* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.symmetric.AES* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.symmetric.ChaCha* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.symmetric.Poly1305* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.RSA* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.EC* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.ec.** { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.DH* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.dh.** { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.EdEC* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.edec.** { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.X509* { public <init>(...); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.asymmetric.x509.** { public <init>(...); }

# Dalvik's Random/SecureRandom constructors call setSeed before subclass fields are assigned.
# Preserve BC's defensive null checks; release optimization otherwise assumes the final source
# field is already initialized and crashes during the first TLS nonce-generator construction.
-keep class org.bouncycastle.crypto.prng.SP800SecureRandom { *; }

# Its optional integrations reference classes that do not exist on Android; they are never loaded.
-dontwarn javax.naming.**
-dontwarn java.sql.**
-dontwarn org.bouncycastle.**
-dontnote org.bouncycastle.**
