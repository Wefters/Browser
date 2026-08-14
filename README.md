# @wefterjs/browser

Official Wefter plugin for opening in-app web browsers using Android Custom Tabs and iOS `SFSafariViewController`.

---

## Features

- 🌐 **In-App Web Browsing**: Opens external web URLs inside high-performance native Custom Tabs / Safari view controllers without leaving the app.
- 🔐 **OAuth / Web Auth**: Native deep-linking support for OAuth2 authentication flows.
- 🎨 **Theme & Toolbar Control**: Custom toolbar tint colors matching your brand design system.
- 📡 **Lifecycle Events**: Real-time event listeners for `opened`, `closed`, and `authCompleted` events.

---

## Installation & Setup

1. Add the plugin to your Wefter project:

```bash
wefter add @wefterjs/browser
```

2. Synchronize native projects:

```bash
wefter sync
```

---

## JavaScript API Reference

Import `invokeNative` and `registerHook` from `@wefterjs/core`:

```ts
import { invokeNative, registerHook } from "@wefterjs/core";
```

### 1. `open(options)`

Opens an in-app browser overlay.

```ts
interface OpenBrowserOptions {
  url: string; // The URL to open
  toolbarColor?: string; // Hex color for the navigation toolbar (e.g., "#4f46e5")
  windowName?: string; // Target window target (default: "_blank")
}

await invokeNative("browser", "open", {
  url: "https://wefter.dev",
  toolbarColor: "#0f172a",
});
```

### 2. `close()`

Programmatically closes the active in-app browser window.

```ts
await invokeNative("browser", "close");
```

### 3. `auth(options)`

Initiates an in-app OAuth authentication flow. Automatically intercepts custom redirect schemes.

```ts
interface AuthOptions {
  url: string; // OAuth login URL
  redirectScheme: string; // Custom app scheme (e.g. "myapp")
}

interface AuthResult {
  url: string; // Full callback URL containing query parameters or authorization code
}

const authResult = await invokeNative<AuthResult>("browser", "auth", {
  url: "https://auth.example.com/login?response_type=code&client_id=123",
  redirectScheme: "myapp",
});

console.log("Returned OAuth URL:", authResult.url);
```

---

## Event Subscriptions

Listen to browser lifecycle changes using `registerHook`:

```ts
import { registerHook } from "@wefterjs/core";

// Listen to browser opened event
const openSub = registerHook("browser:opened", () => {
  console.log("In-app browser opened");
});

// Listen to browser closed event
const closeSub = registerHook("browser:closed", () => {
  console.log("In-app browser closed by user");
});

// Clean up listener when done
closeSub.remove();
```

---

## Complete Usage Example

```ts
import { invokeNative, registerHook } from "@wefterjs/core";

export async function loginWithOAuth() {
  const sub = registerHook("browser:closed", () => {
    console.log("User dismissed login modal");
  });

  try {
    const result = await invokeNative<{ url: string }>("browser", "auth", {
      url: "https://github.com/login/oauth/authorize?client_id=YOUR_CLIENT_ID",
      redirectScheme: "wefter",
    });

    const code = new URL(result.url).searchParams.get("code");
    console.log("Authorization Code:", code);
  } finally {
    sub.remove();
  }
}
```
