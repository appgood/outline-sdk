export interface ConnectivityTestRequest {
    accessKey: string;
    domain: string;
    resolvers: string[];
    protocols: {
        tcp: boolean;
        udp: boolean;
    };
}
export interface ConnectivityTestResult {
    time: string;
    durationMs: number;
    proto: string;
    resolver: string;
    error?: {
        operation: string;
        posixError: string;
        message: string;
    };
}
export type ConnectivityTestResponse = ConnectivityTestResult[] | Error | null;
export declare enum OperatingSystem {
    ANDROID = "android",
    IOS = "ios",
    LINUX = "linux",
    MACOS = "darwin",
    WINDOWS = "windows"
}
export interface PlatformMetadata {
    operatingSystem: OperatingSystem;
}
