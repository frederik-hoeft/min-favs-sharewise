import type { Metadata } from "next";
import { BreadcrumbDemoPage } from "./_components/BreadcrumbDemoPage";
import { breadcrumbDemoRoutes } from "@/app/(app)/app/_components/app-routes";

export const metadata: Metadata = { title: "Dummy 3" };

export default function DummyThreePage() {
  return <BreadcrumbDemoPage title="Dummy 3" next={{ href: breadcrumbDemoRoutes.group, label: "International Collaboration Project" }} />;
}
