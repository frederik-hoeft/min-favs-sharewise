import type { Metadata } from "next";
import { BreadcrumbDemoPage } from "../../../_components/BreadcrumbDemoPage";

export const metadata: Metadata = { title: "Overview" };

export default function DummyThreePage() {
  return <BreadcrumbDemoPage title="Overview" />;
}
