export declare namespace shared_backend {
    class Response {
        body: string;
        error: string;
        static createFrom(source?: any): Response;
        constructor(source?: any);
    }
}
