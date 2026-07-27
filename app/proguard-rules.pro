-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class tools.alamobile.mod.NativeBridge {
    native <methods>;
}
