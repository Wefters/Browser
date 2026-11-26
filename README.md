# @wefterjs/browser

Official Wefter plugin for opening in-app web browsers using Android Custom Tabs and iOS SFSafariViewController, including OAuth authentication flows.

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
  mode: "webview", // "webview" (in-app tab) or "external" (system browser)
  title: "Documentation",
  showToolbar: true,
  showNavigationButtons: true,
  shareButton: true,
  desktopMode: false,
});
```

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

- Uses `androidx.browser.customtabs.CustomTabsIntent` for in-app web views.
- OAuth flows route through `BrowserAuthActivity` to intercept redirect intent callbacks.

### iOS

- Uses `SFSafariViewController` for standard web browsing.
- Uses `ASWebAuthenticationSession` when `Browser.auth` is called, ensuring secure token exchange and private session isolation when `ephemeral: true` is configured.

## License

[MIT](LICENSE) © 2026 Sandip Ghimire
