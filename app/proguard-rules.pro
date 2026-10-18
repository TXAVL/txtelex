# Engine đọc blob bằng code thường, không cần rule riêng.

# Module Dịch on-demand: base nạp lớp này bằng tên (Class.forName) ⇒ R8 không được đổi tên/bỏ.
-keep class com.customkey.vietime.translate.MlKitTranslator { public <init>(); }
-keep interface com.customkey.vietime.tools.OnDeviceTranslator { *; }
