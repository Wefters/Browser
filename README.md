# @wefterjs/browser

Official Wefter plugin for opening web pages in the system browser's in-app view (Android Custom Tabs, iOS `SFSafariViewController`) or the browser app, and for OAuth sign-in flows.

Use it for sites you don't control and for sign-in: the user's own sign-ins, saved passwords and security warnings work there, and the app can't read or script the page. For sites you own or work with that should talk to the app, use `openWebWindow()` from `@wefterjs/core` instead.

## Features

- Display web pages inside the app without redirecting the user to an external browser application.
- Configurable display modes: in-app web view overlay or external browser handoff.
- Customizable toolbar visibility, navigation controls, and share buttons.
- Dedicated OAuth flow handler with redirect URI interception and callback parameter extraction.
- Lifecycle event hooks for browser open, close, and authentication completion events.

## Installation and setup

Install the plugin package in your Wefter application:

```bash
wefter add @wefterjs/browser
wefter sync
```

### Native configuration

When synchronized:

- Android includes the `androidx.browser:browser:1.8.0` dependency and automatically registers `BrowserAuthActivity` for the app's sign-in redirect scheme, `<app id>.auth`.
- iOS uses `SFSafariViewController` and `ASWebAuthenticationSession`.

## JavaScript API reference

Import `Browser` from `@wefterjs/browser`:

```ts
import { Browser } from "@wefterjs/browser";
```

### Open an in-app browser

```ts
await Browser.open({
  url: "https://wefter.dev",
  mode: "inApp", // the default; or "external" for the browser app
  toolbarColor: "#0f766e",
  shareButton: true, // Android
});
```

| Mode       | Opens                                                                                                                                                                           |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `inApp`    | Custom Tabs (Android) or `SFSafariViewController` (iOS). Android uses the browser app when no browser supports Custom Tabs. Only `http` and `https`.                            |
| `external` | The browser app.                                                                                                                                                                |
| `webview`  | **Deprecated.** The plugin's own web view, with `title`, `showToolbar`, `showNavigationButtons` and `desktopMode`. Logs a warning; use `openWebWindow()` from `@wefterjs/core`. |

`browser:opened` and `browser:closed` follow. On Android, a Custom Tab counts as closed when the app comes back to the front.

### Close the active browser

```ts
await Browser.close();
```

### OAuth authentication flows

To run an OAuth2 login flow:

```ts
// 1. Listen for authentication completion
const authSub = Browser.onAuthCompleted(({ callbackUrl, params }) => {
  console.log("Full callback URL:", callbackUrl);
  console.log("Extracted code:", params.code);
  authSub.remove();
});

// 2. Start authentication session
await Browser.auth({
  url: "https://auth.example.com/oauth/authorize?client_id=123&response_type=code",
  redirectUri: await Browser.getRedirectUri(), // e.g. com.example.shop.auth://callback
  ephemeral: true, // Use private session without shared cookies
});
```

The redirect URI uses the app's own scheme: its app id plus `.auth`. Development and production builds have different app ids, so register both redirect URIs with your sign-in provider. On Android the app id must be lower case without underscores to work as a scheme.

### Event listeners

```ts
// Browser opened event
const openSub = Browser.onOpened(({ url, mode }) => {
  console.log("Browser opened:", url, mode);
});

// Browser closed by user or programmatically
const closeSub = Browser.onClosed(({ reason }) => {
  console.log("Browser closed:", reason);
});

// Clean up listeners when needed
openSub.remove();
closeSub.remove();
```

## Platform events

| Event                   | Payload                                                                | Platforms    |
| ----------------------- | ---------------------------------------------------------------------- | ------------ |
| `browser:opened`        | `{ url: string, mode: "webview" \| "external", id?: string }`          | Android, iOS |
| `browser:closed`        | `{ reason: string, id?: string }`                                      | Android, iOS |
| `browser:authCompleted` | `{ callbackUrl: string, params: Record<string, string>, id?: string }` | Android, iOS |

## Platform implementation notes

### Android

- `inApp` uses `androidx.browser.customtabs.CustomTabsIntent`; `close()` brings the app's screen back to the front, which closes the tab.
- OAuth flows route through `BrowserAuthActivity` to intercept redirect intent callbacks.

### iOS

- `inApp` uses `SFSafariViewController`.
- Uses `ASWebAuthenticationSession` when `Browser.auth` is called, ensuring secure token exchange and private session isolation when `ephemeral: true` is configured.

## License

[MIT](LICENSE) © 2026 Sandip Ghimire
