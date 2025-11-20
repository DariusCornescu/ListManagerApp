import 'package:flutter/material.dart';
import 'screens/home_screen.dart';

void main() {
  runApp(const ListManagerCRUDApp());
}

class ListManagerCRUDApp extends StatelessWidget {
  const ListManagerCRUDApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'List Manager - CRUD Only',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color.fromARGB(255, 120, 50, 3)),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
      debugShowCheckedModeBanner: false,
    );
  }
}