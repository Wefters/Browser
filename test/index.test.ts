// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { installMockBridge, uninstallMockBridge } from "@wefterjs/core/testing";
import { WefterBridgeError } from "@wefterjs/core";
import { Browser } from "../src/index.js";

afterEach(() => {
  uninstallMockBridge();
});

describe("Browser.open", () => {
  it("forwards options and resolves with started", async () => {
    installMockBridge({
      browser: (method, payload) => {
        expect(method).toBe("open");
        expect(payload).toEqual({ url: "https://example.com", mode: "external" });
        return { started: true };
      },
    });

    const result = await Browser.open({ url: "https://example.com", mode: "external" });

    expect(result).toEqual({ started: true });
  });

  it("is callable with just a url", async () => {
    installMockBridge({
      browser: (_method, payload) => {
        expect(payload).toEqual({ url: "https://example.com" });
        return { started: true };
      },
    });

    await Browser.open({ url: "https://example.com" });
  });
});

describe("Browser.close", () => {
  it("is callable with no arguments", async () => {
    installMockBridge({
      browser: (method, payload) => {
        expect(method).toBe("close");
        expect(payload).toEqual({});
        return { closed: true };
      },
    });

    const result = await Browser.close();

    expect(result).toEqual({ closed: true });
  });

  it("forwards an id when given one", async () => {
    installMockBridge({
      browser: (_method, payload) => {
        expect(payload).toEqual({ id: "session-1" });
        return { closed: false };
      },
    });

    const result = await Browser.close({ id: "session-1" });

    expect(result).toEqual({ closed: false });
  });
});

describe("Browser.auth", () => {
  it("forwards url/redirectUri and resolves with started", async () => {
    installMockBridge({
      browser: (method, payload) => {
        expect(method).toBe("auth");
        expect(payload).toEqual({
          url: "https://provider.example.com/authorize",
          redirectUri: "wefter://callback",
        });
        return { started: true };
      },
    });

    const result = await Browser.auth({
      url: "https://provider.example.com/authorize",
      redirectUri: "wefter://callback",
    });

    expect(result).toEqual({ started: true });
  });
});

describe("events", () => {
  it("onOpened receives what the native side emits under browser:opened", () => {
    let received: unknown;
    const subscription = Browser.onOpened((data) => {
      received = data;
    });

    window.__wefterNative.emit(
      "browser:opened",
      JSON.stringify({ url: "https://example.com", mode: "webview" }),
    );

    expect(received).toEqual({ url: "https://example.com", mode: "webview" });
    subscription.remove();
  });

  it("onClosed receives what the native side emits under browser:closed", () => {
    let received: unknown;
    const subscription = Browser.onClosed((data) => {
      received = data;
    });

    window.__wefterNative.emit("browser:closed", JSON.stringify({ reason: "user_closed" }));

    expect(received).toEqual({ reason: "user_closed" });
    subscription.remove();
  });

  it("onAuthCompleted receives what the native side emits under browser:authCompleted", () => {
    let received: unknown;
    const subscription = Browser.onAuthCompleted((data) => {
      received = data;
    });

    window.__wefterNative.emit(
      "browser:authCompleted",
      JSON.stringify({ callbackUrl: "wefter://callback?code=abc", params: { code: "abc" } }),
    );

    expect(received).toEqual({ callbackUrl: "wefter://callback?code=abc", params: { code: "abc" } });
    subscription.remove();
  });
});

describe("error propagation", () => {
  it("surfaces a native rejection as a WefterBridgeError", async () => {
    installMockBridge({
      browser: () => {
        throw new Error("A URL must be provided.");
      },
    });

    const call = Browser.open({ url: "" });

    await expect(call).rejects.toBeInstanceOf(WefterBridgeError);
    await expect(call).rejects.toMatchObject({
      code: "MOCK_ERROR",
      message: "A URL must be provided.",
    });
  });
});
