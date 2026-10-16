# Light Chat: Google Cloud Setup Guide

## Step 1: Create a Google Cloud Project

Google manages all app permissions through a portal called the Google Cloud Console.

1. Open your web browser and go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Log in with the exact Google/Gmail account you use for Google Chat.
3. Look at the very top left corner of the screen (next to the "Google Cloud" logo). Click the drop-down menu that says **Select a project** (or click your existing project name if you've been here before).
4. A window will pop up. Click the **New Project** button in the top right of that window.
5. Name your project something recognizable, like `My Light Phone Chat`, and click **Create**. Wait a few seconds for it to finish loading.

## Step 2: Turn on the Google Chat API

An "API" is just a pipeline that lets apps share data. You need to turn on the pipeline for Google Chat.

1. Make sure your new project is selected at the top of the screen.
2. In the left-hand sidebar, click on **APIs & Services**, then select **Library**.
3. In the big search bar in the middle of the page, type `Google Chat API` and press Enter.
4. Click on the Google Chat API result that appears.
5. Click the blue **Enable** button.

## Step 3: Set Up the App Consent Screen

Before Google hands over an access key, it wants to know what your app looks like when a user logs in.

1. In the left sidebar, click on **APIs & Services**, then click **OAuth consent screen**.
2. For **User Type**, choose **External** (this allows you to test it with your own account) and click **Create**.
3. Fill out the mandatory fields:
   - **App name:** Type `Light Chat`
   - **User support email:** Choose your own Gmail address from the dropdown.
   - **Developer contact information:** Type your own Gmail address again.
4. Click **Save and Continue** at the bottom. You can skip the "Scopes" and "Optional Info" pages by clicking **Save and Continue** on those as well until you are back at the summary.
5. **Crucial Step:** On the OAuth summary page, look for the **Test Users** section. Click **Add Users**, type in your own Gmail address, and click **Save**. (Because your app isn't public, Google will block you from logging in unless your email is explicitly added as a test user.)

## Step 4: Generate Your Client ID and Secret (The Keys)

Now you will generate the actual code credentials that your AI coding agent needs to build the login screen.

1. In the left sidebar, click on **Credentials**.
2. At the top of the screen, click the **+ Create Credentials** button and select **OAuth client ID**.
3. Click the **Application type** dropdown menu and select **Desktop app** (even though it runs on Android, choosing "Desktop app" is the standard trick required to let a limited device like the Light Phone do a quick "browser code login").
4. Name it `Light Phone Client` and click **Create**.
5. A window will pop up displaying a **Client ID** (a long string of letters and numbers ending in `.apps.googleusercontent.com`) and a **Client Secret**.

## What to Do Next

Copy both the Client ID and the Client Secret and paste them into a text file, or download the JSON file Google offers you on that screen.
