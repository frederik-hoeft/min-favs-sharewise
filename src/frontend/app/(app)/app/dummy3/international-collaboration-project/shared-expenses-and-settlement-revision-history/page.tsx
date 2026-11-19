import type { Metadata } from "next";
import { BreadcrumbDemoPage } from "../../_components/BreadcrumbDemoPage";
import { breadcrumbDemoRoutes } from "@/app/(app)/app/_components/app-routes";

export const metadata: Metadata = { title: "Shared Expenses and Settlement Revision History" };

export default function DummyThreePage() {
  return <BreadcrumbDemoPage title="Shared Expenses and Settlement Revision History" next={{ href: breadcrumbDemoRoutes.overview, label: "Overview" }} />;
}
