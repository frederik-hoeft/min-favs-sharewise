import { useCallback, useEffect, useRef, useState } from "react";

export type OperationOutcome<T> =
  | { readonly status: "fulfilled"; readonly value: T }
  | { readonly status: "rejected"; readonly error: unknown };

export interface ExclusiveOperation<K extends string> {
  /** The running operation's kind, for rendering; `null` when the owner is idle. */
  readonly pending: K | null;
  /**
   * Whether an operation is running. Unlike `pending`, it reflects a start immediately, so
   * repeated events cannot slip through before React has rendered the busy state.
   */
  isRunning(): boolean;
  /**
   * Starts `request` unless another operation is running, and returns whether it started.
   * `settle` runs only while the owner is still mounted; once the owner unmounts, nothing
   * observes the outcome. Ignoring an outcome does not undo the request: a submitted write
   * may still have committed on the server.
   */
  start<T>(kind: K, request: () => Promise<T>, settle: (outcome: OperationOutcome<T>) => void): boolean;
}

/**
 * Runs at most one asynchronous operation at a time for the component that owns it, and scopes
 * each outcome to that component's lifetime. A form uses it to keep a submission, a reload and
 * any other incompatible action from overlapping, and to keep the continuation of an abandoned
 * operation from changing state or navigating after the user has left.
 */
export function useExclusiveOperation<K extends string>(): ExclusiveOperation<K> {
  const owner = useRef<{ mounted: boolean; running: K | null }>({ mounted: false, running: null });
  const [pending, setPending] = useState<K | null>(null);

  useEffect(() => {
    const state = owner.current;
    state.mounted = true;
    return () => {
      state.mounted = false;
    };
  }, []);

  const isRunning = useCallback(() => owner.current.running !== null, []);

  const start = useCallback(<T,>(kind: K, request: () => Promise<T>, settle: (outcome: OperationOutcome<T>) => void) => {
    const state = owner.current;
    if (state.running !== null) return false;
    state.running = kind;
    setPending(kind);

    let operation: Promise<T>;
    try {
      operation = request();
    } catch (error) {
      operation = Promise.reject(error);
    }
    void operation.then(
      (value): OperationOutcome<T> => ({ status: "fulfilled", value }),
      (error: unknown): OperationOutcome<T> => ({ status: "rejected", error }),
    ).then((outcome) => {
      if (!state.mounted) return;
      state.running = null;
      setPending(null);
      settle(outcome);
    });
    return true;
  }, []);

  return { pending, isRunning, start };
}
