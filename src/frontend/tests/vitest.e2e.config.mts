import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// Explicit deployment-dependent suite; the normal Unit glob never matches it.
export default defineConfig({
    root: fileURLToPath(new URL('..', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('..', import.meta.url)) } },
    test: {
        environment: 'node',
        include: ['tests/deployed/**/*.e2e.ts'],
        passWithNoTests: false,
        testTimeout: 60_000,
        hookTimeout: 60_000,
        fileParallelism: false,
    },
});
