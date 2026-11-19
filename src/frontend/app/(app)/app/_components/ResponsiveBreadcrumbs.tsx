"use client";

import { Anchor, Breadcrumbs, Menu, Text, UnstyledButton } from "@mantine/core";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import type { AppBreadcrumb } from "./app-routes";
import styles from "./ResponsiveBreadcrumbs.module.scss";

type Props = { crumbs: AppBreadcrumb[] };

/**
 * The full trail is measured at its intrinsic width, independently of the
 * rendered (potentially collapsed) trail. This avoids breakpoint guesses and
 * oscillation when the ellipsis is narrower than the omitted items.
 */
export function ResponsiveBreadcrumbs({ crumbs }: Props) {
  const availableRef = useRef<HTMLElement>(null);
  const fullRef = useRef<HTMLDivElement>(null);
  const [collapsed, setCollapsed] = useState(crumbs.length > 2);
  const path = crumbs.map((crumb) => crumb.href).join("|");

  useEffect(() => {
    if (crumbs.length <= 2) return;

    const update = () => {
      const available = availableRef.current?.getBoundingClientRect().width ?? 0;
      const required = fullRef.current?.getBoundingClientRect().width ?? 0;
      // An unmeasurable first frame (including jsdom) stays safely collapsed.
      setCollapsed(!available || !required || required > available);
    };

    update();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(update);
    if (availableRef.current) observer.observe(availableRef.current);
    if (fullRef.current) observer.observe(fullRef.current);
    return () => observer.disconnect();
  }, [path, crumbs.length]);

  const hiddenCrumbs = crumbs.slice(1, -1);
  const entries: (AppBreadcrumb | null)[] = collapsed && crumbs.length > 2
    ? [crumbs[0], null, crumbs[crumbs.length - 1]] : crumbs;

  return (
    <nav className={styles.navigation} aria-label="Breadcrumb" ref={availableRef}>
      <Breadcrumbs separator="/" separatorMargin="xs" classNames={{ root: styles.row }}>
        {entries.map((crumb, index) => {
          if (crumb === null) {
            return (
              <Menu key="omitted" position="bottom-start" withinPortal shadow="sm">
                <Menu.Target>
                  <UnstyledButton className={styles.disclosure} aria-label={`Show ${hiddenCrumbs.length} omitted breadcrumb locations`} title="Show omitted locations">
                    …
                  </UnstyledButton>
                </Menu.Target>
                <Menu.Dropdown className={styles.dropdown} aria-label="Omitted breadcrumb locations">
                  {hiddenCrumbs.map(({ href, label }) => (
                    <Menu.Item key={href} component={Link} href={href} classNames={{ itemLabel: styles.menuLabel }}>
                      {label}
                    </Menu.Item>
                  ))}
                </Menu.Dropdown>
              </Menu>
            );
          }

          return index === entries.length - 1 ? (
            <Text span size="sm" key={crumb.href} aria-current="page" className={styles.current} title={crumb.label}>
              {crumb.label}
            </Text>
          ) : (
            <Anchor component={Link} size="sm" key={crumb.href} href={crumb.href} className={styles.link}>
              {crumb.label}
            </Anchor>
          );
        })}
      </Breadcrumbs>
      {/* Intrinsic-width reference, invisible and never part of the accessible trail. */}
      {crumbs.length > 2 && (
        <div className={styles.measure} ref={fullRef} aria-hidden="true">
          <Breadcrumbs separator="/" separatorMargin="xs" classNames={{ root: styles.measureRow }}>
            {crumbs.map(({ href, label }) => <Text span size="sm" key={href}>{label}</Text>)}
          </Breadcrumbs>
        </div>
      )}
    </nav>
  );
}
