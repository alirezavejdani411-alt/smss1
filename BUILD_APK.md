# راهنمای ساخت APK (برای کسی که Android Studio دارد)

1. پوشه‌ی پروژه را در Android Studio باز کنید: File > Open
2. صبر کنید Gradle Sync تمام شود (اگر پیام نصب Build Tools 34.0.0 یا SDK 35 آمد، Install بزنید).
3. از منو: Build > Build Bundle(s) / APK(s) > Build APK(s)
4. فایل خروجی اینجاست:
   app/build/outputs/apk/debug/app-debug.apk
5. همین فایل را برای صاحب پروژه بفرستید.

نیازمندی‌ها: JDK 17، Android SDK 35، Build Tools 34.0.0

## ساخت با GitHub Actions (بدون Android Studio)
1. یک مخزن (repository) جدید در GitHub بسازید و همه‌ی فایل‌های این پروژه (از جمله پوشه‌ی .github) را آپلود کنید.
2. به تب Actions بروید؛ ساخت خودکار شروع می‌شود (چند دقیقه).
3. وقتی سبز شد، روی آن اجرا کلیک کنید و از پایین صفحه، Artifacts > SMS-Controller-APK را دانلود کنید.
