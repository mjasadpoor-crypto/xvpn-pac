function FindProxyForURL(url, host) {
    host = host.toLowerCase();

    if (host == "api.github.com" ||
        host == "raw.githubusercontent.com") {
        return "DIRECT";
    }

    if (host == "ir" || dnsDomainIs(host, ".ir")) {
        return "DIRECT";
    }
return "PROXY 127.0.0.1:8080";
}
