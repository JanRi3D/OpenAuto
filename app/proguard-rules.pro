# BouncyCastle: only the lightweight TLS client API is used (no JCE provider registration).
# Its optional integrations reference classes that do not exist on Android; they are never loaded.
-dontwarn javax.naming.**
-dontwarn java.sql.**
-dontwarn org.bouncycastle.**
-dontnote org.bouncycastle.**
