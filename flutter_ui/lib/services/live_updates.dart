import 'dart:async';
import 'dart:convert';

import 'package:web_socket_channel/web_socket_channel.dart';

typedef ChannelFactory = WebSocketChannel Function(Uri uri);

/// The server's real-time events, with reconnection. Events only say that something
/// changed; the state always comes from a fresh read of the REST API.
class LiveUpdates {
  LiveUpdates({
    required this.baseUrl,
    required this.accessToken,
    ChannelFactory? channelFactory,
    this.maxBackoff = const Duration(seconds: 30),
  }) : _connect = channelFactory ?? WebSocketChannel.connect;

  final String baseUrl;
  final String? Function() accessToken;
  final Duration maxBackoff;
  final ChannelFactory _connect;

  final _events = StreamController<String>.broadcast();
  StreamSubscription<dynamic>? _subscription;
  Timer? _reconnect;
  WebSocketChannel? _channel;
  Duration _backoff = const Duration(seconds: 1);
  bool _running = false;
  bool _reconnecting = false;

  /// Sent after the socket comes back. Events aren't replayed, so anything that
  /// happened during the gap has to be fetched again.
  static const reconnected = 'live_reconnected';

  /// The type of each event, such as `session_item_added`.
  Stream<String> get events => _events.stream;

  Uri socketUri(String token) {
    final http = Uri.parse(baseUrl);
    return http.replace(
      scheme: http.scheme == 'https' ? 'wss' : 'ws',
      path: '/ws',
      queryParameters: {'token': token},
    );
  }

  void start() {
    if (_running) return;
    _running = true;
    _open();
  }

  Future<void> stop() async {
    _running = false;
    _reconnect?.cancel();
    await _subscription?.cancel();
    // Not awaited: on a dead network the other side may never acknowledge the close.
    unawaited(_channel?.sink.close());
    _channel = null;
  }

  void _open() {
    final token = accessToken();
    if (!_running) return;
    if (token == null) {
      _scheduleReconnect();
      return;
    }
    try {
      final channel = _connect(socketUri(token));
      _channel = channel;
      unawaited(channel.ready.then((_) => _onConnected(channel), onError: (Object _) {}));
      _subscription = channel.stream.listen(
        _onMessage,
        onDone: _scheduleReconnect,
        onError: (Object _) => _scheduleReconnect(),
        cancelOnError: true,
      );
    } on Exception {
      _scheduleReconnect();
    }
  }

  void _onConnected(WebSocketChannel channel) {
    if (!_running || !_reconnecting || !identical(channel, _channel)) return;
    _reconnecting = false;
    _events.add(reconnected);
  }

  void _onMessage(dynamic raw) {
    _backoff = const Duration(seconds: 1);
    try {
      final type = (jsonDecode(raw as String) as Map<String, dynamic>)['type'];
      if (type is String) _events.add(type);
    } on FormatException {
      // Not an event; nothing to refresh.
    } on TypeError {
      // Same.
    }
  }

  void _scheduleReconnect() {
    if (!_running) return;
    _reconnecting = true;
    _reconnect?.cancel();
    final wait = _backoff;
    _backoff = _backoff * 2 > maxBackoff ? maxBackoff : _backoff * 2;
    _reconnect = Timer(wait, _open);
  }

  Future<void> dispose() async {
    await stop();
    await _events.close();
  }
}
