এই ফোল্ডারে (app/ এর ভেতরে, app/build.gradle এর পাশে) আপনার
আসল google-services.json ফাইলটা রাখতে হবে।

কীভাবে পাবেন:
1. https://console.firebase.google.com এ আপনার প্রজেক্টে যান
2. ⚙️ Project settings -> নিচে "Your apps" এ "Add app" -> Android
3. Package name: com.rinagift.app (অথবা আপনি যদি অন্য package
   name দিয়ে build করেন, ঠিক সেটাই এখানে দিতে হবে)
4. "Register app" -> google-services.json ডাউনলোড করুন
5. ডাউনলোড হওয়া ফাইলটা এই ফোল্ডারে আপলোড করুন, নাম ঠিক
   "google-services.json" রাখবেন
6. এই টেক্সট ফাইলটা (README-google-services.txt) ডিলিট করে
   দিতে পারেন, এটা শুধু নির্দেশনার জন্য

⚠️ গুরুত্বপূর্ণ: package name যা দিয়ে Firebase-এ app register
করবেন এবং GitHub Actions-এর "application_id" ফিল্ডে যা দেবেন —
এই দুটো অবশ্যই এক হতে হবে, নাহলে Push Notification কাজ করবে না।

⚠️ এই একই প্রজেক্ট দিয়ে ভবিষ্যতে অন্য কোনো ভিন্ন অ্যাপ (ভিন্ন
package name) বানালে, সেই অ্যাপের জন্য আলাদাভাবে Firebase-এ
register করে আলাদা google-services.json বসাতে হবে।
