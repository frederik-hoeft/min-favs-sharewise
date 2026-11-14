import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { ApiError, createApiClient } from './client.ts';

describe('createApiClient', () => {
    it('resolves typed JSON data on 200 OK', async () => {
        const client = createApiClient({}, async (input, init) => {
            assert.equal(input, '/api/system/v1/health');
            assert.equal(init?.credentials, 'same-origin');
            return new Response(JSON.stringify({ status: 'UP' }), {
                status: 200,
                headers: { 'content-type': 'application/json' },
            });
        });

        const result = await client.get<{ status: string }>('/system/v1/health');
        assert.deepEqual(result, { status: 'UP' });
    });

    it('resolves undefined on 204 No Content', async () => {
        const client = createApiClient({}, async () => {
            return new Response(null, { status: 204 });
        });

        const result = await client.delete<void>('/api/items/1');
        assert.equal(result, undefined);
    });

    it('parses 400 ProblemDetail with fieldErrors into ApiError', async () => {
        const client = createApiClient({}, async () => {
            return new Response(
                JSON.stringify({
                    type: 'about:blank',
                    title: 'Validation Failed',
                    status: 400,
                    detail: 'Request validation failed.',
                    code: 'validation_failed',
                    fieldErrors: [
                        { field: 'amount', code: 'must_be_positive', message: 'must be greater than 0' },
                    ],
                }),
                {
                    status: 400,
                    headers: { 'content-type': 'application/problem+json' },
                }
            );
        });

        await assert.rejects(
            () => client.post('/api/expenses', { amount: 0 }),
            (error: unknown) => {
                assert.ok(error instanceof ApiError);
                assert.equal(error.status, 400);
                assert.equal(error.code, 'validation_failed');
                assert.equal(error.title, 'Validation Failed');
                assert.equal(error.detail, 'Request validation failed.');
                assert.deepEqual(error.fieldErrors, [
                    { field: 'amount', code: 'must_be_positive', message: 'must be greater than 0' },
                ]);
                return true;
            }
        );
    });

    it('uses the HTTP response status as authoritative when ProblemDetail status mismatches', async () => {
        const client = createApiClient({}, async () => {
            return new Response(
                JSON.stringify({
                    type: 'about:blank',
                    title: 'Forbidden',
                    status: 403,
                    detail: 'Session expired at gateway.',
                    code: 'token_expired',
                }),
                {
                    status: 401,
                    headers: { 'content-type': 'application/problem+json' },
                }
            );
        });

        await assert.rejects(
            () => client.get('/api/groups'),
            (error: unknown) => {
                assert.ok(error instanceof ApiError);
                assert.equal(error.status, 401);
                assert.equal(error.code, 'token_expired');
                assert.equal(error.title, 'Forbidden');
                assert.equal(error.detail, 'Session expired at gateway.');
                return true;
            }
        );
    });

    it('maps network failures to ApiError with status 0 and network_error code', async () => {
        const client = createApiClient({}, async () => {
            throw new TypeError('Failed to fetch');
        });

        await assert.rejects(
            () => client.get('/api/system/v1/health'),
            (error: unknown) => {
                assert.ok(error instanceof ApiError);
                assert.equal(error.status, 0);
                assert.equal(error.code, 'network_error');
                assert.equal(error.detail, 'Failed to fetch');
                return true;
            }
        );
    });
});
