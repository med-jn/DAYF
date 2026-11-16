صفحة الدعوات (استضافة ثابتة مجانية على Cloudflare Pages)

1) Cloudflare ← Workers & Pages ← Create ← Pages ← Upload assets.
2) اسم المشروع: tikguest  (فيصبح العنوان https://tik.orcaup.com).
3) ارفع محتوى هذا المجلد كله (web) كما هو، بما فيه المجلد المخفي .well-known والملفان _redirects و _headers.
4) إن اختلف العنوان عما سبق فغيّر السطر shareHost في gradle.properties ثم أعد بناء التطبيق.
5) تحقق: افتح https://<العنوان>/.well-known/assetlinks.json فيجب أن يظهر JSON.
