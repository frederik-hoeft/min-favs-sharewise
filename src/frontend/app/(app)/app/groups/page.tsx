import type { Metadata } from "next";
import { GroupsOverview } from "./_components/GroupsOverview";

export const metadata: Metadata = { title: "Groups" };

export default function GroupsPage() {
  return <GroupsOverview />;
}
