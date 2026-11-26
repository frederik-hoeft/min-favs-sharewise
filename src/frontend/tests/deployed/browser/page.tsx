"use client";

import { Alert, Button, Code, Group, Stack, Text, TextInput, Title } from "@mantine/core";
import { useState, type ChangeEvent, type Dispatch, type ReactElement, type SetStateAction } from "react";
import { useAuthenticatedApis } from "@/app/identity-provider";
import { GroupsApi } from "@/core/groups/groups-api";
import type { GroupDetails } from "@/core/groups/models";
import { ApiError, type ApiClient } from "@/lib/api";
import { useExclusiveOperation, type ExclusiveOperation, type OperationOutcome } from "@/lib/hooks/use-exclusive-operation";

const ROOT: string = "/smoke/v1";
interface Effects { readonly effects: number }
type Action = () => Promise<unknown>;

/** Copied into the route tree only by the explicit disposable browser image. */
export default function AuthorizationSmokePage(): ReactElement {
    const { api }: { readonly api: ApiClient } = useAuthenticatedApis();
    const [source, setSource]: [string, Dispatch<SetStateAction<string>>] = useState("");
    const [destination, setDestination]: [string, Dispatch<SetStateAction<string>>] = useState("");
    const [timestamp, setTimestamp]: [string, Dispatch<SetStateAction<string>>] = useState("1");
    const operation: ExclusiveOperation<"request"> = useExclusiveOperation<"request">();
    const busy: boolean = operation.pending !== null;
    const [result, setResult]: [string, Dispatch<SetStateAction<string>>] = useState("Create two disposable groups to begin.");
    const sourcePath: string = encodeURIComponent(source);
    const destinationParam: string = encodeURIComponent(destination);
    const timestampParam: string = encodeURIComponent(timestamp);

    function run(label: string, action: Action): void {
        operation.start("request", action, (outcome: OperationOutcome<unknown>): void => {
            if (outcome.status === "fulfilled") {
                setResult(`${label}: ${JSON.stringify(outcome.value ?? null)}`);
                return;
            }
            const error: unknown = outcome.error;
            setResult(error instanceof ApiError ? `${label}: ${error.status} ${error.code}: ${error.detail}` : `${label}: request failed`);
        });
    }

    async function setup(): Promise<readonly string[]> {
        // Temporary IAM-to-Group projection seam, confined to this test artifact.
        await api.post<void>(`${ROOT}/seed-user`, undefined, { expectedStatus: 204 });
        const groups: GroupsApi = new GroupsApi(api);
        const x: GroupDetails = await groups.create({ name: "Authorization smoke X" });
        const y: GroupDetails = await groups.create({ name: "Authorization smoke Y" });
        setSource(x.id);
        setDestination(y.id);
        return [x.id, y.id];
    }

    function action(label: string, request: Action, requiresSource: boolean = true, requiresDestination: boolean = false): ReactElement {
        return <Button key={label} variant="default" disabled={busy || (requiresSource && !source) || (requiresDestination && !destination)}
            onClick={(): void => run(label, request)}>{label}</Button>;
    }

    return <Stack p="lg" maw={920} mx="auto">
        <Title order={1}>Distributed authorization smoke</Title>
        <Alert color="yellow" title="Disposable test deployment only">
            This page uses the actual authenticated client. Open the browser Network panel (Preserve log) to inspect challenges,
            issuance, replay and warm requests. Do not copy credential headers or cookies into reports.
        </Alert>
        <Group>
            {action("Seed caller and create two groups", setup, false)}
            {action("Read effect count", (): Promise<Effects> => api.get(`${ROOT}/effects`), false)}
        </Group>
        <TextInput label="Source / target group UUID" value={source} disabled={busy}
            onChange={(event: ChangeEvent<HTMLInputElement>): void => setSource(event.currentTarget.value)} />
        <TextInput label="Destination group UUID" value={destination} disabled={busy}
            onChange={(event: ChangeEvent<HTMLInputElement>): void => setDestination(event.currentTarget.value)} />
        <TextInput label="Timestamp query parameter" value={timestamp} disabled={busy}
            onChange={(event: ChangeEvent<HTMLInputElement>): void => setTimestamp(event.currentTarget.value)} />
        <Title order={2}>Protected operations</Title>
        <Group>
            {action("Path-affine read", (): Promise<Effects> => api.get(`${ROOT}/path/${sourcePath}`))}
            {action("Other endpoint (same credential)", (): Promise<Effects> => api.get(`${ROOT}/other/${sourcePath}`))}
            {action("Request-affine read", (): Promise<Effects> => api.get(`${ROOT}/request/${sourcePath}?timestamp=${timestampParam}`))}
            {action("Query-affine read", (): Promise<Effects> => api.get(`${ROOT}/query?target=${sourcePath}&timestamp=${timestampParam}`))}
            {action("Path-affine mutation", (): Promise<Effects> => api.post(`${ROOT}/path/${sourcePath}`, {}))}
            {action("Body-dependent mutation", (): Promise<Effects> => api.post(`${ROOT}/body`, { target: source, timestamp: Number(timestamp) }))}
            {action("Changing requirement", (): Promise<Effects> => api.post(`${ROOT}/dynamic`, { target: source, timestamp: Number(timestamp) }))}
            {action("Three-requirement transfer", (): Promise<Effects> => api.post(`${ROOT}/transfer/${sourcePath}?destination=${destinationParam}`, {}), true, true)}
        </Group>
        <Title order={2}>Recovery failures</Title>
        <Group>
            {action("Repeated challenge", (): Promise<Effects> => api.post(`${ROOT}/failure/repeat`, {}), false)}
            {action("Recursive acquisition", (): Promise<Effects> => api.post(`${ROOT}/failure/recursive`, {}), false)}
            {action("Unavailable authority", (): Promise<Effects> => api.post(`${ROOT}/failure/unavailable`, {}), false)}
        </Group>
        <Text fw={500}>Last outcome</Text>
        <Code block role="status">{result}</Code>
        <Text size="sm">Reload to clear the in-memory caches. Leave the page open for two minutes to exercise lazy attestation renewal.</Text>
    </Stack>;
}
