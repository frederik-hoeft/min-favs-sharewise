'use client';

import { useEffect, useState } from "react";
import styles from "./greetings.module.css";

export default function GreetingsPage() {
    const [message, setMessage] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);

    useEffect(() => {
        const fetchData = async () => {
            try {
                const res = await fetch('/api/greetings', {
                    cache: 'no-store'
                });
                if (!res.ok) throw new Error('Failed to fetch data');
                const data = await res.json();
                setMessage(data.message);
            } catch (error) {
                if (error instanceof Error) {
                    setError(error);
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
