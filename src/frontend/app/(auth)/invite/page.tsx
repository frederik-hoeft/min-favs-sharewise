import type { Metadata } from "next";
import { InvitationAcceptance } from "./InvitationAcceptance";

export const metadata: Metadata = { title: "Join a group | ShareWise" };

export default function InvitePage() {
  return <InvitationAcceptance />;
}
