import { useCallback, useEffect, useRef, useState } from "react";
import { classifyGroupProblem, type GroupProblem } from "@/core/groups";

export type GroupResourceState<T> =
  | { status: "loading" }
  | { status: "ready"; data: T }
  | { status: "failed"; problem: GroupProblem };

export interface GroupResource<T> {
  state: GroupResourceState<T>;
  /** Loads again, showing the loading state; resolves with the current loaded data, or null on failure, supersession or disposal. */
  reload: () => Promise<T | null>;
  /** Replaces loaded data with a fresher server response, such as a successful update; supersedes pending loads. */
  replace: (data: T) => void;
}

/**
 * Loads one Group Management resource on mount. The latest load or replacement wins: a
 * response that a newer load or `replace` has superseded is ignored, as is any response that
 * arrives after unmounting. Callers remount the component through a `key` when the resource
 * identity changes.
 */
export function useGroupResource<T>(load: () => Promise<T>): GroupResource<T> {
  const [state, setState] = useState<GroupResourceState<T>>({ status: "loading" });
  // Incremented by every load, replacement and unmount; only the holder of the latest value may write.
  const generation = useRef(0);

  const settle = useCallback((loading: Promise<T>): Promise<T | null> => {
    const own = ++generation.current;
    return loading.then(
      (data) => {
        if (generation.current !== own) return null;
        setState({ status: "ready", data });
        return data;
      },
      (error: unknown) => {
        if (generation.current === own) setState({ status: "failed", problem: classifyGroupProblem(error) });
        return null;
      },
    );
  }, []);

  useEffect(() => {
    void settle(load());
    const current = generation;
    return () => {
      current.current += 1;
    };
  }, [load, settle]);

  const reload = useCallback(() => {
    setState({ status: "loading" });
    return settle(load());
  }, [load, settle]);

  const replace = useCallback((data: T) => {
    generation.current += 1;
    setState({ status: "ready", data });
  }, []);

  return { state, reload, replace };
}
