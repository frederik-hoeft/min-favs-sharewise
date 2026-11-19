import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// UI tests use jsdom; API-client tests use the Node environment.
// The shared setup must not access browser globals in Node workers.
if (typeof window !== "undefined") {
  // jsdom does not implement matchMedia; Mantine uses it for system color schemes.
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string): MediaQueryList => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });

  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });
}
