'use client';

import { useEffect, useState } from "react";
import { apiClient, ApiError } from "@/lib/api";
import styles from "./greetings.module.css";

interface GreetingResponse {
    readonly message: string;
}

export default function GreetingsPage() {
    const [message, setMessage] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);

    useEffect(() => {
        const fetchData = async () => {
            try {
                const data = await apiClient.get<GreetingResponse>('/api/greetings');
                setMessage(data.message);
            } catch (err) {
                if (err instanceof ApiError || err instanceof Error) {
                    setError(err);
                }
            } finally {
                setLoading(false);
            }
        };

        fetchData();
    }, []);

    if (loading) return <div>Loading...</div>;
    if (error) return <div>Error: {error.message}</div>;

    return (
        <main>
            <h1 className={styles.message}>{message} from backend ...</h1>
        </main>
    );
}
