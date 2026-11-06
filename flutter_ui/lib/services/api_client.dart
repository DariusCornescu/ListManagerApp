import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import 'token_store.dart';

/// The server answered with an error.
class ApiException implements Exception {
  const ApiException(this.statusCode, this.message);

  final int statusCode;
  final String message;

  bool get isConflict => statusCode == 409;
  bool get isNotFound => statusCode == 404;

  @override
  String toString() => 'ApiException($statusCode): $message';
}

/// No answer at all: offline, timeout, or a server that is down. Never ends the login.
class NetworkException implements Exception {
  const NetworkException();

  @override
  String toString() => 'NetworkException';
}

enum _RefreshResult { refreshed, rejected, unavailable }

class ApiClient {
  ApiClient({
    required this.baseUrl,
    required this.tokens,
    http.Client? httpClient,
    this.onSessionExpired,
    this.timeout = const Duration(seconds: 30),
  }) : _http = httpClient ?? http.Client();

  final String baseUrl;
  final TokenStore tokens;
  final Duration timeout;

  /// Called when the login cannot be recovered and the user must sign in again.
  void Function()? onSessionExpired;

  final http.Client _http;
  Future<_RefreshResult>? _refreshing;

  Future<dynamic> get(String path, {Map<String, String>? query}) =>
      _request('GET', path, query: query);

  Future<dynamic> post(String path, {Object? body}) =>
      _request('POST', path, body: body);

  Future<dynamic> put(String path, {Object? body}) =>
      _request('PUT', path, body: body);

  Future<dynamic> delete(String path) => _request('DELETE', path);

  Future<dynamic> _request(
    String method,
    String path, {
    Map<String, String>? query,
    Object? body,
  }) async {
    final sentAccess = tokens.current?.access;
    var response = await _send(method, path, query, body, sentAccess);

    if (response.statusCode == 401 && sentAccess != null) {
      switch (await _refreshOnce(sentAccess)) {
        case _RefreshResult.refreshed:
          response = await _send(method, path, query, body, tokens.current?.access);
        case _RefreshResult.unavailable:
          throw const NetworkException();
        case _RefreshResult.rejected:
          await tokens.clear();
          onSessionExpired?.call();
      }
    }
    return _decode(response);
  }

  Future<http.Response> _send(
    String method,
    String path,
    Map<String, String>? query,
    Object? body,
    String? access,
  ) async {
    final request = http.Request(
      method,
      Uri.parse('$baseUrl$path').replace(queryParameters: query),
    );
    if (access != null) request.headers['Authorization'] = 'Bearer $access';
    if (body != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(body);
    }
    try {
      return await http.Response.fromStream(
        await _http.send(request).timeout(timeout),
      );
    } on http.ClientException {
      throw const NetworkException();
    } on TimeoutException {
      throw const NetworkException();
    }
  }

  /// Requests that fail together share one refresh: a second one would rotate the
  /// token the first just saved.
  Future<_RefreshResult> _refreshOnce(String sentAccess) {
    if (tokens.current != null && tokens.current!.access != sentAccess) {
      return Future.value(_RefreshResult.refreshed);
    }
    return _refreshing ??= _refresh().whenComplete(() => _refreshing = null);
  }

  Future<_RefreshResult> _refresh() async {
    final refresh = tokens.current?.refresh;
    if (refresh == null) return _RefreshResult.rejected;
    final http.Response response;
    try {
      response = await _send('POST', '/api/auth/refresh', null, {
        'refresh_token': refresh,
      }, null);
    } on NetworkException {
      return _RefreshResult.unavailable;
    }
    if (response.statusCode == 401 || response.statusCode == 403) {
      return _RefreshResult.rejected;
    }
    if (response.statusCode != 200) return _RefreshResult.unavailable;

    final json = jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
    await tokens.save(
      Tokens(
        access: json['access_token'] as String,
        refresh: json['refresh_token'] as String?,
      ),
    );
    return _RefreshResult.refreshed;
  }

  dynamic _decode(http.Response response) {
    final text = utf8.decode(response.bodyBytes);
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return text.isEmpty ? null : jsonDecode(text);
    }
    throw ApiException(response.statusCode, _detailOf(text));
  }

  String _detailOf(String text) {
    try {
      final detail = (jsonDecode(text) as Map<String, dynamic>)['detail'];
      if (detail is String) return detail;
      if (detail is Map && detail['message'] is String) return detail['message'] as String;
    } on FormatException {
      // Not JSON: a proxy error page, for instance.
    } on TypeError {
      // JSON, but not the usual shape.
    }
    return 'Eroare de server';
  }
}
