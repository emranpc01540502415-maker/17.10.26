====================================================
 Rina Gift Shop — Android App (চূড়ান্ত সম্পূর্ণ ভার্সন)
====================================================

এই zip-এ আপনার সবগুলো ফিচার অক্ষত আছে (Smart Login, Geofence,
In-App Update, Install Referrer, Push Notification, Dark Mode,
Crash Reporting, App Shortcuts, Deep Link) এবং google-services.json
ফাইলটাও ইতিমধ্যে ভেতরে বসানো আছে। এই README অনুযায়ী নতুন
Repository বানিয়ে সেটআপ করলে ১০০% কাজ করার কথা।

----------------------------------------------------
ধাপ ১ — পুরনো Repository গুলো ডিলিট করুন (ঐচ্ছিক কিন্তু পরামর্শ)
----------------------------------------------------
বারবার সমস্যা হয়েছে এমন পুরনো repository (যেমন: advapp, adv)
রেখে দিলে বিভ্রান্তি বাড়বে। ডিলিট করতে:

1. GitHub-এ সেই repository-তে যান
2. উপরে "Settings" ট্যাবে ক্লিক করুন
3. একদম নিচে "Danger Zone" সেকশনে "Delete this repository" চাপুন
4. নিশ্চিত করতে repository-র নাম টাইপ করতে বলবে, টাইপ করে ডিলিট করুন
5. একই নিয়মে বাকি পুরনো repository গুলোও (advapp, adv, ইত্যাদি) ডিলিট করুন

----------------------------------------------------
ধাপ ২ — নতুন Repository তৈরি করুন
----------------------------------------------------
1. GitHub-এ উপরে ডানদিকে "+" -> "New repository"
2. একটা পরিষ্কার নাম দিন, যেমন:
       rina-gift-shop-android
   (এই নামটা সহজ, বোঝা যায়, এবং আগের কোনো নামের সাথে গুলিয়ে
   যাবে না)
3. Public বা Private — যেকোনোটা
4. "Create repository" চাপুন

----------------------------------------------------
ধাপ ৩ — সব ফাইল একবারে আপলোড করুন
----------------------------------------------------
1. এই zip Extract করুন
2. নতুন repository পেজে "uploading an existing file" ক্লিক করুন
3. Extract করা ফোল্ডারের ভেতরের সবকিছু ড্র্যাগ করে দিন:
       - app  (পুরো ফোল্ডার — google-services.json সহ ভেতরেই আছে)
       - build.gradle
       - settings.gradle
       - gradle.properties
   (হিডেন .github ফোল্ডার/.gitignore এই ধাপে বাদ যেতে পারে,
   পরের ধাপে আলাদাভাবে করব)
4. "Commit changes"

----------------------------------------------------
ধাপ ৪ — GitHub Actions workflow ফাইল যোগ করুন
----------------------------------------------------
1. Repository পেজে "Add file" -> "Create new file"
2. ফাইলের নামের জায়গায় ঠিক এভাবে টাইপ করুন:
       .github/workflows/build-apk.yml
3. এই zip-এর ভেতরের .github/workflows/build-apk.yml ফাইলটা
   Notepad-এ খুলে পুরো কন্টেন্ট কপি করে পেস্ট করুন
4. "Commit changes"

----------------------------------------------------
ধাপ ৫ — বিল্ড চালান
----------------------------------------------------
1. "Actions" ট্যাব -> "Build Android APK" -> "Run workflow"
2. ফর্মে (ডিফল্ট ভ্যালুই এবার সঠিক দেওয়া আছে, তাও মিলিয়ে দেখুন):
       admin_base_url: https://rinagift.shop/amp
       app_name: Rina Gift Shop
       application_id: com.rinagift.app
3. সবুজ "Run workflow" বাটনে ক্লিক করুন
4. ১-৩ মিনিট অপেক্ষা করুন, সবুজ ✓ দেখা গেলে সফল

----------------------------------------------------
ধাপ ৬ — ডাউনলোড ও ইনস্টল
----------------------------------------------------
1. সফল রানে ক্লিক করুন -> নিচে "Artifacts" -> "app-debug-apk"
   ডাউনলোড করুন
2. Extract করলে app-debug.apk পাবেন
3. ফোনে পাঠিয়ে ইনস্টল করুন ("Install unknown apps" পারমিশন দিতে হতে পারে)

====================================================
 ✅ এই ভার্সনে যা যা আছে (সবকিছু অক্ষত)
====================================================
✅ ওয়েবসাইট লোড (Admin Panel থেকে নিয়ন্ত্রিত URL/Maintenance/
   Force Update/Zoom/Pull-to-refresh/Swipe-back)
✅ লোডিং progress bar + ২০ সেকেন্ড timeout protection
✅ Push Notification (google-services.json এবার যোগ করা আছে,
   তাই ফোন বন্ধ/ব্যাকগ্রাউন্ডে থাকলেও নোটিফিকেশন যাবে)
✅ In-app Popup notification (view/click ট্র্যাকিং সহ)
✅ Live visitor heartbeat + device registration
✅ WhatsApp/Call/Custom ফ্লোটিং বাটন
✅ App Rating popup
✅ Dark Mode (ফোনের সিস্টেম সেটিং অনুযায়ী স্বয়ংক্রিয়)
✅ Smart Login (Phone Number Hint + Biometric/PIN)
✅ Deep Link (শেয়ার করা লিংক অ্যাপে খোলা)
✅ Geofence (লোকেশন-ভিত্তিক নোটিফিকেশন — Admin Panel-এ চালু
   করতে হবে "Smart Features" পেজ থেকে)
✅ In-App Update (Play Store ইনস্টলে কাজ করে)
✅ Install Referrer ট্র্যাকিং (Play Store ইনস্টলে কাজ করে)
✅ Crash Reporting (Admin Panel-এ "Crash Reports" পেজে দেখা যাবে)
✅ App Shortcuts (আইকনে লং-প্রেস করলে "অফার"/"অর্ডার ট্র্যাক")

----------------------------------------------------
⚠️ এই দুটো বিষয় শুধু জেনে রাখুন (এরর না, স্বাভাবিক সীমাবদ্ধতা)
----------------------------------------------------
- **Deep Link** ও **In-App Update** পুরোপুরি সক্রিয় হতে Play
  Store-এ আপলোডের সময়কার কিছু অতিরিক্ত ধাপ লাগবে (assetlinks.json,
  Play Store distribution) — এখন সাইডলোড APK-তে এই দুটো ফিচার
  চুপচাপ নিষ্ক্রিয় থাকবে, বাকি সব স্বাভাবিক কাজ করবে।
- **Autofill** (নাম/ফোন/ঠিকানা) কমন ফিল্ড-প্যাটার্ন অনুমান করে
  কাজ করে। আপনার চেকআউট ফর্মের আসল field name/id জানালে ১০০%
  নির্ভুল করে দেওয়া যাবে।

----------------------------------------------------
🔁 ভবিষ্যতে ভিন্ন অ্যাপ বানাতে চাইলে
----------------------------------------------------
এই একই repository থেকে আবার "Run workflow" চালান, ফর্মে ভিন্ন
admin_base_url/app_name/application_id দিন — নতুন repository
লাগবে না। (নতুন Firebase project হলে নতুন google-services.json
লাগবে সেই ভিন্ন অ্যাপের জন্য।)

কোনো সমস্যা হলে স্ক্রিনশট পাঠাবেন।
====================================================


====================================================
 v7 আপডেট — Build ফেল হলে (সমস্যা সমাধান গাইড)
====================================================

কী বদলানো হয়েছে (build ঠিক করতে):
 • workflow এখন ubuntu-24.04-এ পিন করা (ubuntu-latest অক্টোবর ২০২৬-এ Ubuntu 26-এ সরছে)।
 • ফেল করা "android-actions/setup-android" ধাপ বাদ — GitHub runner-এ SDK আগে থেকেই আছে।
 • Gradle 8.7 নিজে ডাউনলোড হয় (AGP 8.5-এর জন্য ঠিক ভার্সন দরকার)।
 • workflow নিজেই রিপোর কাঠামো খোঁজে (root-এ settings.gradle অথবা android-app/ ফোল্ডারে)।
 • ইনপুট (URL, package, রং, version) আগে যাচাই হয় — ভুল হলে পরিষ্কার বাংলা/ইংরেজি error দেখায়।
 • google-services.json-এর package না মিললেও build সফল হয় (শুধু Push বন্ধ থাকে + সতর্কতা)।
 • build ফেল করলে আসল error "Summary" পেজের Annotations-এ দেখায় এবং build-log artifact সেভ হয়।
 • App Shortcuts সঠিক জায়গায় (launcher activity-র ভেতরে) সরানো; allowBackup=false।

নতুন করে চালানোর ধাপ:
 1. রিপোর পুরোনো ফাইল (app/, build.gradle, settings.gradle, gradle.properties) এই zip-এর ফাইল দিয়ে replace করুন।
 2. .github/workflows/build-apk.yml ফাইলটি GitHub-এর ওয়েবে "Add file → Create new file" দিয়ে
    নাম লিখে (.github/workflows/build-apk.yml) পুরো লেখা পেস্ট করে সেভ করুন
    (ড্র্যাগ-ড্রপে hidden ফোল্ডার যায় না)।
 3. Actions → "Build Android APK" → Run workflow।
 4. সফল হলে Summary পেজের নিচে "app-debug-apk" ডাউনলোড করুন।

ফেল করলে:
 • Run পেজের Summary → "Annotations"-এ লাল লাইন পড়ুন (Kotlin/Gradle error সেখানেই আসবে)।
 • তবুও না বুঝলে "build-log" artifact ডাউনলোড করে বা লাল ধাপটির শেষ ২০ লাইন আমাকে পাঠান।

⚠️ পরিচিত সীমাবদ্ধতা: এই workflow শুধু debug APK বানায় (সরাসরি ইনস্টলের জন্য)।
Play Store-এর জন্য release বিল্ড + নিজস্ব keystore এবং targetSdk ৩৫+ লাগবে — পরে আলাদাভাবে করতে হবে।
