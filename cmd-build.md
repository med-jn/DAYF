# APK للتجربة (debug)
gradlew assembleDebug
# يخرج في: app/build/outputs/apk/debug/

# APK release
gradlew assembleRelease
# يخرج في: app/build/outputs/apk/release/

# AAB لمتجر Play
gradlew bundleRelease
# يخرج في: app/build/outputs/bundle/release/

--------------------------

gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep jsoup

------------------------

# مرة واحدة: الإقران (استخدم IP والمنفذ ورمز الإقران الظاهرة في شاشة الإقران)
adb pair 192.168.1.x:PAIRPORT
# ثم الاتصال (المنفذ هنا هو المعروض في الشاشة الرئيسية للتصحيح اللاسلكي، وهو يختلف عن منفذ الإقران)
adb connect 192.168.1.x:PORT

# تأكد أن الجهاز ظهر
adb devices

# بناء وتثبيت دفعة واحدة
gradlew installDebug

# أو تثبيت APK جاهز فوق النسخة القديمة
adb install -r app/build/outputs/apk/debug/app-debug.apk

# تشغيل التطبيق
adb shell am start -n com.tikguest/.MainActivity

adb logcat -d -s AndroidRuntime:E | tail -40

==========================
==========================

   cd /d "C:\Users\lumina\Desktop\ALL PROJECTS\DAYF"
   gradlew installDebug

==========================
==========================
   
   gradlew installDebug
adb shell am start -n com.tikguest/app.dayf.MainActivity
