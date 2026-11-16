# Tik Guest: الإعداد والبناء والنشر

الحزمة: `com.tikguest` (لا تتغير بعد النشر). الإصدار يُدار من `version.properties`.

## 1) تسجيل الدخول بجوجل (مرة واحدة)
**في Google Cloud ← Google Auth Platform:**
1. Clients ← أنشئ عميلاً من نوع **Web application** (هذا الذي نزّلتَ ملفه `client_secret_….json`).
2. أضف في **Authorized redirect URIs**:
   `https://mrlpokoaelgyylpxckjl.supabase.co/auth/v1/callback`
3. Branding: اسم التطبيق وشعاره وبريدك. Audience: غيّر الحالة إلى **In production** (وإلا يعمل لمختبرين فقط وتنتهي الجلسة بعد 7 أيام). الصلاحيات المطلوبة `openid` و`email` و`profile` فقط، ولا تحتاج مراجعة.

**في Supabase:**
4. Authentication ← Providers ← **Google**: فعّله وضع **Client ID** و**Client Secret** من ذلك الملف.
5. Authentication ← URL Configuration ← **Redirect URLs**: أضف `com.tikguest://auth-callback`
6. SQL Editor: شغّل `supabase/schema_v2.sql` كاملاً (مشروع جديد)، أو `supabase/schema_update_1.sql` فقط إن كنت شغّلت v2 من قبل (لا يحذف شيئاً). تنبيه: يحذف جداول الاختبار القديمة. وإن اشتكى من vault فعّل `supabase_vault` من Database ← Extensions ثم أعد التشغيل.

> ⚠ ملف `client_secret_….json` سرّي: لا ترفعه إلى GitHub ولا تضعه في التطبيق. مكانه الوحيد حقول Supabase أعلاه، ثم احذفه أو احفظه خارج المشروع.
> لا حاجة لعميل Android ولا لبصمة SHA-1 في هذا المسار (الدخول عبر المتصفح). تحتاجها فقط إن أضفنا لاحقاً الدخول الأصلي (One Tap).

## 2) البناء من VS Code (بديل GitHub)
المتطلبات: JDK 17 وAndroid SDK (عندك من مشروع Capacitor).
```
.\build.ps1          # يرفع رقم الإصدار ويبني، والناتج في dist\
.\build.ps1 -NoBump  # بناء دون رفع الرقم
```
الناتج: `TikGuest-1.0.N.apk` (للتثبيت المباشر) و`TikGuest-1.0.N.aab` (للمتجر). في macOS/Linux استعمل `./build.sh`.
GitHub Actions يبقى اختيارياً: يبني الاثنين تلقائياً عند كل push ويضعهما في Releases.
قاعدة واحدة: رقم الإصدار يُرفع **محلياً فقط** (`build.ps1`) ثم تعمل commit لـ `version.properties`، حتى لا يتعارض ترتيب الإصدارات في المتجر.

## 3) التوقيع والبصمات
| المفتاح | الاستعمال | مكانه |
|---|---|---|
| `app/tikguest-dev.keystore` | APK للتثبيت المباشر (يجعل التحديثات تعمل) | داخل المستودع (للتجربة فقط) |
| مفتاح الرفع (upload) | توقيع AAB للمتجر | سري عندك |
| مفتاح توقيع Play | تحتفظ به Google | Play Console |

بصمة المفتاح التجريبي:
`SHA-1: 80:0F:49:F3:42:94:CB:93:EA:9E:8F:D0:8E:27:E8:BA:25:51:8E:DE`

**توليد مفتاح الرفع (مرة واحدة، على حاسوبك):**
```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```
احفظ `upload.jks` وكلمة السر في مكانين آمنين خارج المستودع.
- **محلياً:** أنشئ ملف `keystore.properties` في جذر المشروع (مستثنى من git):
```
storeFile=upload.jks
storePassword=كلمة_السر
keyAlias=upload
keyPassword=كلمة_السر
```
فيُوقَّع الـ AAB به تلقائياً.
- **على GitHub (اختياري):** أضف أسراراً `UPLOAD_KEYSTORE_BASE64` و`UPLOAD_STORE_PASSWORD` و`UPLOAD_KEY_ALIAS` و`UPLOAD_KEY_PASSWORD`. لتحويل الملف (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks")) | Set-Clipboard`

**لرؤية بصمة مفتاح الرفع:** `keytool -list -v -keystore upload.jks -alias upload`
بصمة مفتاح توقيع Play تظهر في Play Console ← Test and release ← App integrity ← بعد أول رفع.

## 4) قائمة Play Console
- Play App Signing: فعّله (الافتراضي).
- سياسة الخصوصية: فعّل GitHub Pages من مجلد `docs/` وضع الرابط في Play Console وفي `Config.PRIVACY_URL`، بعد أن تملأ الحقول بين [ ].
- حذف الحساب: موجود داخل التطبيق، وأضف رابط صفحة الخصوصية كرابط للحذف.
- Data safety: صرّح بالبريد ومعرّف المستخدم، وبأن الصوت ولقطات الشاشة تُرسل إلى Google (Gemini) أثناء التشغيل دون تخزين عندنا، وبأن المفاتيح تُخزَّن مشفّرة.
- نوع خدمة المقدمة `mediaProjection` والظهور فوق التطبيقات: تحتاج تصريحاً وشرحاً (وربما فيديو قصيراً) في نموذج الأذونات.
- المحتوى المُولَّد بالذكاء الاصطناعي: زر «🚩 إبلاغ» موجود في اللوحة العائمة (شرط المتجر).
- حساب مطوّر شخصي جديد: اختبار مغلق (عدد من المختبرين لمدة 14 يوماً) قبل النشر العام.
- استهداف Android 16 (API 36) إلزامي للتطبيقات الجديدة منذ 31 أغسطس 2026. التطبيق الآن على API 34. الترقية خطوتنا التالية، وهي تشمل رفع أدوات البناء وضبط حواف الشاشة.

## 5) ما حُذف عمداً
خدمة الإتاحة (Accessibility): سياسة المتجر تقيّدها بأدوات إتاحة الوصول، والتحقق من أنك ضيف يتم بالصورة عند الحاجة.

## 6) المراجع والمكتبات (قسم المعرفة ← المراجع)
- **رابط / PDF / صورة / مقال:** يقرؤه Gemini مرة واحدة بمفتاحك ويكتب منه خلاصة مفصلة يعتمدها الضيف. المصدر يجب أن يكون رابطاً عاماً مباشراً (الصفحات المحمية بحساب أو الدفع لن تُقرأ).
- **فيديو يوتيوب:** فيديو واحد عام (القنوات الكاملة غير مدعومة).
- **مكتبة Supabase (مشروع آخر):** يبحث الضيف فيها مباشرة دون نسخ محتواها. تحتاج: رابط المشروع، ومفتاح قراءة عام (anon/publishable)، واسم الجدول، وعمود العنوان، واختيارياً عمود النص والرابط وأعمدة البحث. يجب أن تسمح سياسة RLS في مشروع المكتبة بالقراءة بذلك المفتاح. لا تستعمل مفتاح `service_role` أبداً. المفتاح يُحفظ مشفّراً في حسابك.
- كل قراءة تستهلك من حصة مفتاح Gemini الخاص بالمستخدم.
