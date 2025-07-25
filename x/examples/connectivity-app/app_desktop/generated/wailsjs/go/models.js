export var shared_backend;
(function (shared_backend) {
    var Response = /** @class */ (function () {
        function Response(source) {
            if (source === void 0) { source = {}; }
            if ('string' === typeof source)
                source = JSON.parse(source);
            this.body = source["body"];
            this.error = source["error"];
        }
        Response.createFrom = function (source) {
            if (source === void 0) { source = {}; }
            return new Response(source);
        };
        return Response;
    }());
    shared_backend.Response = Response;
})(shared_backend || (shared_backend = {}));
//# sourceMappingURL=models.js.map