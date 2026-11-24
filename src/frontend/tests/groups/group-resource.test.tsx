import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useGroupResource } from "@/app/(app)/app/groups/_components/use-group-resource";
import { deferred, type Deferred } from "./support";

/** A loader whose calls each return a promise that the test settles explicitly. */
function scriptedLoads() {
  const pending: Deferred<string>[] = [];
  const load = () => {
    const next = deferred<string>();
    pending.push(next);
    return next.promise;
  };
  return { load, pending };
}

describe("useGroupResource", () => {
  it("keeps a replacement when a superseded load responds later", async () => {
    const loads = scriptedLoads();
    const { result } = renderHook(() => useGroupResource(loads.load));
    await act(async () => loads.pending[0].resolve("initial"));
    expect(result.current.state).toEqual({ status: "ready", data: "initial" });

    let reloaded: Promise<string | null> = Promise.resolve(null);
    act(() => {
      reloaded = result.current.reload();
    });
    act(() => result.current.replace("saved"));
    await act(async () => {
      loads.pending[1].resolve("stale");
      await reloaded;
    });
    expect(result.current.state).toEqual({ status: "ready", data: "saved" });
  });

  it("applies only the latest of overlapping loads", async () => {
    const loads = scriptedLoads();
    const { result } = renderHook(() => useGroupResource(loads.load));
    act(() => {
      void result.current.reload();
    });
    await act(async () => loads.pending[1].resolve("newer"));
    await act(async () => loads.pending[0].resolve("older"));
    expect(result.current.state).toEqual({ status: "ready", data: "newer" });
  });
});
