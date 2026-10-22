import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../state/app_state.dart';

/// Firebase Cloud Messaging for admin notifications and "refresh apps".
///
/// Configured from `--dart-define`s so no google-services.json is needed:
/// FIREBASE_API_KEY, FIREBASE_APP_ID, FIREBASE_SENDER_ID, FIREBASE_PROJECT_ID.
class PushService {
  PushService._(this._state);

  static const _apiKey = String.fromEnvironment('FIREBASE_API_KEY');
  static const _appId = String.fromEnvironment('FIREBASE_APP_ID');
  static const _senderId = String.fromEnvironment('FIREBASE_SENDER_ID');
  static const _projectId = String.fromEnvironment('FIREBASE_PROJECT_ID');

  static bool get configured =>
      !kIsWeb &&
      defaultTargetPlatform == TargetPlatform.android &&
      _apiKey.isNotEmpty &&
      _appId.isNotEmpty &&
      _senderId.isNotEmpty &&
      _projectId.isNotEmpty;

  final AppState _state;
  bool? _registeredFor;

  /// Starts push if configured and the app is connected to the server.
  static Future<PushService?> start(AppState state) async {
    if (!configured || state.api == null) return null;
    try {
      await Firebase.initializeApp(
        options: const FirebaseOptions(
          apiKey: _apiKey,
          appId: _appId,
          messagingSenderId: _senderId,
          projectId: _projectId,
        ),
      );
      final service = PushService._(state);
      await service._init();
      return service;
    } catch (e) {
      debugPrint('UhuruMax push disabled: $e');
      return null;
    }
  }

  Future<void> _init() async {
    final messaging = FirebaseMessaging.instance;
    FirebaseMessaging.onMessage.listen(_onData);
    FirebaseMessaging.onMessageOpenedApp.listen(_onData);
    messaging.onTokenRefresh.listen((_) {
      _registeredFor = null;
      _sync();
    });
    _state.addListener(_sync);
    await _sync();
  }

  void _onData(RemoteMessage message) {
    if (message.data['type'] == 'refresh') {
      _state.requestRestart();
    } else {
      _state.sync(force: true);
    }
  }

  /// Sends the token while notifications are on, and clears it when off.
  Future<void> _sync() async {
    final wanted = _state.notifications;
    if (_registeredFor == wanted) return;
    _registeredFor = wanted;
    try {
      String token = '';
      if (wanted) {
        final settings = await FirebaseMessaging.instance.requestPermission();
        if (settings.authorizationStatus != AuthorizationStatus.denied) {
          token = await FirebaseMessaging.instance.getToken() ?? '';
        }
      }
      await _state.api!.savePushToken(token);
    } catch (e) {
      _registeredFor = null;
      debugPrint('UhuruMax push token not saved: $e');
    }
  }
}
