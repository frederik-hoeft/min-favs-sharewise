import type { Metadata } from "next";
import { CreateGroup } from "@/app/(app)/app/groups/_components/CreateGroup";

export const metadata: Metadata = { title: "Create a group" };

export default function NewGroupPage() {
  return <CreateGroup />;
}
