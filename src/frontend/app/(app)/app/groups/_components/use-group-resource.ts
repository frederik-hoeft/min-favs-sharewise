import { useCallback, useEffect, useState } from "react";
import { classifyGroupProblem, type GroupProblem } from "@/core/groups";

export type GroupResourceState<T> =
  | { status: "loading" }
  | { status: "ready"; data: T }
  | { status: "failed"; problem: GroupProblem };

export interface GroupResource<T> {
  state: GroupResourceState<T>;
  /** Loads again, showing the loading state; resolves with the loaded data, or null on failure. */
  reload: () => Promise<T | null>;
  /** Replaces loaded data with a fresher server response, such as a successful update. */
  replace: (data: T) => void;
}

/**
 * Loads one Group Management resource on mount. Responses that arrive after unmounting are
 * ignored. Callers remount the component through a `key` when the resource identity changes.
 */
export function useGroupResource<T>(load: () => Promise<T>): GroupResource<T> {
  const [state, setState] = useState<GroupResourceState<T>>({ status: "loading" });

  const settle = useCallback((loading: Promise<T>, isCurrent: () => boolean): Promise<T | null> => loading.then(
    (data) => {
      if (isCurrent()) setState({ status: "ready", data });
      return data;
    },
    (error: unknown) => {
      if (isCurrent()) setState({ status: "failed", problem: classifyGroupProblem(error) });
      return null;
    },
  ), []);

  useEffect(() => {
    let current = true;
    void settle(load(), () => current);
    return () => {
      current = false;
    };
  }, [load, settle]);

  const reload = useCallback(() => {
    setState({ status: "loading" });
    return settle(load(), () => true);
  }, [load, settle]);

  const replace = useCallback((data: T) => setState({ status: "ready", data }), []);

  return { state, reload, replace };
}
