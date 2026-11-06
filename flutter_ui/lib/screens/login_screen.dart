import 'package:flutter/material.dart';

import '../services/api_client.dart';
import '../state/auth_controller.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.auth});

  final AuthController auth;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();

  bool _isRegistering = false;
  bool _isBusy = false;
  String? _error;

  @override
  void dispose() {
    _username.dispose();
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _isBusy = true;
      _error = null;
    });
    try {
      final username = _username.text.trim();
      if (_isRegistering) {
        await widget.auth.register(username, _email.text.trim(), _password.text);
      } else {
        await widget.auth.login(username, _password.text);
      }
    } on NetworkException {
      _showError('Nu se poate conecta la server. Verificați conexiunea.');
    } on ApiException catch (e) {
      _showError(switch (e.statusCode) {
        401 => 'Utilizator sau parolă incorectă',
        429 => 'Prea multe încercări. Așteptați un minut.',
        _ => e.message,
      });
    }
    if (mounted) setState(() => _isBusy = false);
  }

  void _showError(String message) {
    if (mounted) setState(() => _error = message);
  }

  @override
  Widget build(BuildContext context) {
    final expired = widget.auth.sessionExpired;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      'List Manager',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                    const SizedBox(height: 24),
                    if (expired && _error == null) ...[
                      Text(
                        'Sesiune expirată. Autentificați-vă din nou.',
                        style: TextStyle(color: Theme.of(context).colorScheme.error),
                      ),
                      const SizedBox(height: 12),
                    ],
                    TextFormField(
                      controller: _username,
                      decoration: const InputDecoration(labelText: 'Utilizator'),
                      textInputAction: TextInputAction.next,
                      autofillHints: const [AutofillHints.username],
                      validator: (v) =>
                          (v == null || v.trim().isEmpty) ? 'Completați utilizatorul' : null,
                    ),
                    if (_isRegistering) ...[
                      const SizedBox(height: 12),
                      TextFormField(
                        controller: _email,
                        decoration: const InputDecoration(labelText: 'Email'),
                        keyboardType: TextInputType.emailAddress,
                        textInputAction: TextInputAction.next,
                        validator: (v) =>
                            (v == null || !v.contains('@')) ? 'Email invalid' : null,
                      ),
                    ],
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _password,
                      decoration: const InputDecoration(labelText: 'Parolă'),
                      obscureText: true,
                      onFieldSubmitted: (_) => _submit(),
                      autofillHints: const [AutofillHints.password],
                      validator: (v) {
                        if (v == null || v.isEmpty) return 'Completați parola';
                        if (_isRegistering && v.length < 6) {
                          return 'Parola trebuie să aibă cel puțin 6 caractere';
                        }
                        return null;
                      },
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        _error!,
                        style: TextStyle(color: Theme.of(context).colorScheme.error),
                      ),
                    ],
                    const SizedBox(height: 24),
                    FilledButton(
                      onPressed: _isBusy ? null : _submit,
                      child: Text(_isRegistering ? 'Creează cont' : 'Autentificare'),
                    ),
                    TextButton(
                      onPressed: _isBusy
                          ? null
                          : () => setState(() {
                              _isRegistering = !_isRegistering;
                              _error = null;
                            }),
                      child: Text(
                        _isRegistering ? 'Am deja cont' : 'Nu ai cont? Creează unul',
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
