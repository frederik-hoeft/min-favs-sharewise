import type { Metadata } from "next";
import { Suspense } from "react";
import { GroupDetailsView } from "../_components/GroupDetailsView";
import { GroupLoadingState } from "../_components/GroupProblemState";

export const metadata: Metadata = { title: "Group" };

/** The query parameter is read in the browser; the static shell shows the loading state. */
export default function GroupViewPage() {
  return (
    <Suspense fallback={<GroupLoadingState label="Loading group…" />}>
      <GroupDetailsView />
    </Suspense>
  );
}
