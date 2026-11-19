import type { Metadata } from "next";
import { BreadcrumbDemoPage } from "../_components/BreadcrumbDemoPage";
import { breadcrumbDemoRoutes } from "@/app/(app)/app/_components/app-routes";

export const metadata: Metadata = { title: "International Collaboration Project" };

export default function DummyThreePage() {
  return <BreadcrumbDemoPage title="International Collaboration Project" next={{ href: breadcrumbDemoRoutes.history, label: "Shared Expenses and Settlement Revision History" }} />;
}
