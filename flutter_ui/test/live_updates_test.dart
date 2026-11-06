import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_ui/services/live_updates.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'package:stream_channel/stream_channel.dart';

class _FakeChannel extends StreamChannelMixin<dynamic> implements WebSocketChannel {
  final incoming = StreamController<dynamic>();
  final outgoing = StreamController<dynamic>();

  @override
  Stream<dynamic> get stream => incoming.stream;

  @override
  Future<void> get ready => Future<void>.value();

  @override
  WebSocketSink get sink => _FakeSink(outgoing);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _FakeSink implements WebSocketSink {
  _FakeSink(this._controller);

  final StreamController<dynamic> _controller;

  @override
  Future<void> close([int? closeCode, String? closeReason]) => _controller.close();

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  test('uses ws or wss to match the API, and puts the token in the query', () {
    expect(
      LiveUpdates(baseUrl: 'http://10.0.2.2:8000', accessToken: () => 't').socketUri('t').toString(),
      'ws://10.0.2.2:8000/ws?token=t',
    );
    expect(
      LiveUpdates(baseUrl: 'https://api.example.com', accessToken: () => 't').socketUri('t').scheme,
      'wss',
    );
  });

  test('forwards event types and ignores anything malformed', () async {
    final channel = _FakeChannel();
    final live = LiveUpdates(
      baseUrl: 'http://server.test',
      accessToken: () => 't',
      channelFactory: (_) => channel,
    );
    final seen = <String>[];
    live.events.listen(seen.add);
    live.start();

    channel.incoming.add('{"type":"session_item_added","data":{}}');
    channel.incoming.add('not json');
    channel.incoming.add('[1,2]');
    channel.incoming.add('{"type":"pong"}');
    await pumpEventQueue();

    expect(seen, ['session_item_added', 'pong']);
    await live.dispose();
  });

  test('reconnects after the socket closes, using the current token', () async {
    final uris = <Uri>[];
    final channels = <_FakeChannel>[];
    var token = 'first';
    final live = LiveUpdates(
      baseUrl: 'http://server.test',
      accessToken: () => token,
      channelFactory: (uri) {
        uris.add(uri);
        final channel = _FakeChannel();
        channels.add(channel);
        return channel;
      },
    );

    live.start();
    token = 'second';
    await channels.single.incoming.close();
    await Future<void>.delayed(const Duration(milliseconds: 1100));

    expect(uris.map((u) => u.queryParameters['token']), ['first', 'second']);
    await live.dispose();
  });

  test('announces a reconnect, but not the first connection', () async {
    final channels = <_FakeChannel>[];
    final live = LiveUpdates(
      baseUrl: 'http://server.test',
      accessToken: () => 't',
      channelFactory: (_) {
        final channel = _FakeChannel();
        channels.add(channel);
        return channel;
      },
    );
    final seen = <String>[];
    live.events.listen(seen.add);

    live.start();
    await pumpEventQueue();
    expect(seen, isEmpty);

    await channels.single.incoming.close();
    await Future<void>.delayed(const Duration(milliseconds: 1100));

    expect(channels, hasLength(2));
    expect(seen, [LiveUpdates.reconnected]);
    await live.dispose();
  });

  test('stopping cancels the reconnect', () async {
    var connects = 0;
    final channel = _FakeChannel();
    final live = LiveUpdates(
      baseUrl: 'http://server.test',
      accessToken: () => 't',
      channelFactory: (_) {
        connects++;
        return channel;
      },
    );

    live.start();
    await channel.incoming.close();
    await live.stop();
    await Future<void>.delayed(const Duration(milliseconds: 1100));

    expect(connects, 1);
  });
}
