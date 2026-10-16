# Light Phone III: Developer Setup Guide

## Step 1: Unlock "Android Developer Mode" on Your Phone

By default, the Light Phone hides its internal Android settings to keep things simple. You need to use a secret code to unlock them.

1. Pick up your [Light Phone III](https://www.google.com/search?ibp=oshop&prds=pvt:hg,pvo:29,imageDocid:16747656724343032882,headlineOfferDocid:13859377328176197359,productDocid:13859377328176197359&q=product&sa=X&ved=2ahUKEwjAzf-Lwb2XAxW3MDQIHaOsGjAQxa4PegYIAAgOEAI) and open the Phone / Dialer tool (where you type phone numbers).
2. Dial this exact code just like a phone number: `*7412369#`
3. The screen will instantly change to a hidden Debug Menu.
4. Scroll through this menu, find **Android Dev Mode**, and tap the switch to turn it ON.
5. Press the back button or home button to exit this menu.

## Step 2: Grant Permission on the Web Dashboard

Light Phone requires you to officially declare on your account website that you want to test custom apps.

1. On your Mac's web browser, go to the [Light Phone Dashboard](https://dashboard.thelightphone.com/) and log in.
2. Click on **Settings** under your device profile.
3. Scroll down until you see the **Developer Mode** section.
4. Click the slider to turn **Developer Mode** ON.
5. Underneath that slider, look for a setting that says **Allowed Tools**. Change this setting from "Light Approved Only" to **Allow All Tools**. (This tells the phone it is allowed to run the custom Google Chat app you are making.)

## Step 3: Turn on USB Debugging on the Phone

Now that the hidden settings are unlocked, we need to tell the phone to trust your Mac when plugged in.

1. On your Light Phone, go to the main **Settings** app.
2. You will see a brand new menu option called **Developer** or **System**. Tap it.
3. Scroll down until you find **USB Debugging** and turn it ON.

## Step 4: Hook It Up to Your Mac

1. Plug one end of your USB-C cable into your Light Phone and the other end into your Mac.
2. Wake up your Light Phone screen. You should see a pop-up window appear asking: "Allow USB debugging?"
3. Check the box that says **"Always allow from this computer"** and tap **Allow**.
