# In-app updates — one-time setup (about 5 minutes)

After this, every time you merge a pull request, GitHub builds Kinetic by itself.
Kinetic then shows **"Kinetic update ready"**, and one tap installs it. You won't
need Android Studio for updates any more.

## Why there's a setup step

Android only installs an update if it's signed with the **same key** as the app
already on your phone. The Kinetic on your phone was signed by Android Studio with
your computer's "debug key". So GitHub needs a copy of that same key, kept as a
private secret that only GitHub's build can read.

## Steps

1. **Copy your key as text.** On your PC, open the Start menu, type **PowerShell** and open it.
   - Paste this line and press Enter:
     `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard`
   - Nothing visible happens; the key is now on your clipboard as one long line of text.
2. **Open your repo's secrets.** On github.com, open **gabrielchowaniec0-svg/android**, then go to **Settings ▸ Secrets and variables ▸ Actions**.
3. **Save the key.** Tap **New repository secret**.
   - Name: `KINETIC_KEYSTORE_BASE64`
   - Secret: paste (Ctrl+V).
   - Tap **Add secret**.
   - The debug key's password and name are the standard ones, so no other secrets are needed.
4. **Run the first build.** Go to the **Actions** tab ▸ **Release** ▸ **Run workflow** ▸ **Run workflow**. It takes about 5–8 minutes. When it finishes, a new item appears under **Releases** on the repo's main page.
5. **Allow installs on your phone.** Open Kinetic ▸ app list ▸ ⋮ ▸ **Kinetic settings** ▸ **Updates** ▸ *"First time: allow Kinetic to install updates"*, and switch it on.
6. **Install.** Tap **Check now** ▸ **Update**, then confirm Android's prompt. Kinetic restarts on the new build.

From then on, after each merge Kinetic offers the update by itself. It checks
every few hours, or straight away in Settings ▸ Updates.

## If it says "signed with a different key"

That means the build on your phone wasn't made with the same key. This can happen
if you installed Kinetic from another computer. Uninstall Kinetic once, then
install the latest build from Settings ▸ Updates or from the GitHub Releases page.
After that, all updates install normally.

## Good to know

- Builds made with Android Studio and builds made by GitHub get the same version
  number for the same code. That number is the count of commits, so the updater
  always knows which is newer.
- Every pull request is also test-built by GitHub (the **Build check**). If it
  shows a red ✗, there's a mistake to fix before merging, and I can read
  the error myself.
