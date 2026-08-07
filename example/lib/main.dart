import 'package:flutter/material.dart';

import 'package:ezviz_plugins/ezviz_plugins.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _ezviz = EzvizControl();
  String _status = 'Unknown';

  @override
  void initState() {
    super.initState();
    _initEzviz();
  }

  Future<void> _initEzviz() async {
    String status;
    try {
      // 初始化萤石 SDK（取代宿主 Application.onCreate 中的 initLib）。
      final result = await _ezviz.init();
      status =
          'init ok=${result['ok']} sdkPresent=${result['sdkPresent']}'
          '${result['error'] != null ? ' error=${result['error']}' : ''}';
    } on EzvizException catch (e) {
      status = 'EzvizException: $e';
    }

    if (!mounted) return;
    setState(() {
      _status = status;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('ezviz_plugins example')),
        body: Center(child: Text('Ezviz SDK: $_status\n')),
      ),
    );
  }
}
