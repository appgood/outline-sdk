import { LitElement, nothing } from "lit";
import { ConnectivityTestRequest, ConnectivityTestResponse, ConnectivityTestResult, PlatformMetadata } from "./types";
export * from "./types";
export declare class ConnectivityTestPage extends LitElement {
    loadPlatform?: () => Promise<PlatformMetadata>;
    platform?: PlatformMetadata;
    onSubmit?: (request: ConnectivityTestRequest) => Promise<ConnectivityTestResponse>;
    isSubmitting: boolean;
    response?: ConnectivityTestResponse;
    get locale(): string;
    set locale(newLocale: string);
    get formData(): {
        accessKey: string;
        domain: string;
        resolvers: string[];
        protocols: {
            tcp: boolean;
            udp: boolean;
        };
        prefix: string | undefined;
    } | null;
    protected performUpdate(): Promise<void>;
    testConnectivity(event: SubmitEvent): Promise<void>;
    static styles: import("lit").CSSResult;
    render(): import("lit-html").TemplateResult<1>;
    renderResults(): typeof nothing | import("lit-html").TemplateResult<1>;
    renderResultsList(response: ConnectivityTestResult[] | Error): import("lit-html").TemplateResult<1>;
}
