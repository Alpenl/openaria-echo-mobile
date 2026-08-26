import 'package:flutter/material.dart';

import 'app_controller.dart';
import 'models.dart';

class OpenAriaEchoMobileApp extends StatelessWidget {
  const OpenAriaEchoMobileApp({super.key});

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xff0f766e);
    return MaterialApp(
      title: 'Open Aria Echo',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: seed),
        useMaterial3: true,
        cardTheme: const CardThemeData(
          elevation: 0,
          margin: EdgeInsets.zero,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(8)),
            side: BorderSide(color: Color(0xffd6d8dc)),
          ),
        ),
        navigationBarTheme: const NavigationBarThemeData(
          labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        ),
      ),
      home: const EchoHome(),
    );
  }
}

class EchoHome extends StatefulWidget {
  const EchoHome({super.key});

  @override
  State<EchoHome> createState() => _EchoHomeState();
}

class _EchoHomeState extends State<EchoHome> {
  late final OpenAriaController controller;
  final manualAddress = TextEditingController(text: '10.42.0.1:8080');
  final token = TextEditingController();
  final displayName = TextEditingController();
  final wifiPassphrase = TextEditingController();
  int tab = 0;

  @override
  void initState() {
    super.initState();
    controller = OpenAriaController();
    WidgetsBinding.instance.addPostFrameCallback((_) => controller.scan());
  }

  @override
  void dispose() {
    controller.dispose();
    manualAddress.dispose();
    token.dispose();
    displayName.dispose();
    wifiPassphrase.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return controller.connected
            ? _connectedScaffold(context)
            : _discoveryScaffold(context);
      },
    );
  }

  Widget _discoveryScaffold(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Open Aria Echo'),
        actions: [
          IconButton(
            tooltip: 'Scan LAN',
            icon: const Icon(Icons.radar),
            onPressed: controller.scanning ? null : controller.scan,
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _sectionTitle('Devices'),
            Text(
              'Find Conductor devices on this LAN, or connect by address. mDNS only discovers candidates; the app probes Device API v4 before connecting.',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 16),
            _manualConnectCard(),
            const SizedBox(height: 16),
            if (controller.error != null)
              _ErrorBanner(message: controller.error!),
            if (controller.scanning)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: LinearProgressIndicator(),
              ),
            ...controller.endpoints.map(_endpointTile),
            if (!controller.scanning && controller.endpoints.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 24),
                child: Center(child: Text('No Open Aria devices found yet.')),
              ),
          ],
        ),
      ),
    );
  }

  Widget _manualConnectCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          children: [
            TextField(
              controller: manualAddress,
              decoration: const InputDecoration(
                labelText: 'Device address',
                prefixIcon: Icon(Icons.language),
                hintText: '10.42.0.1:8080 or https://rp-ylx.local',
              ),
              keyboardType: TextInputType.url,
            ),
            const SizedBox(height: 10),
            TextField(
              controller: token,
              decoration: const InputDecoration(
                labelText: 'Access token',
                prefixIcon: Icon(Icons.key),
              ),
              obscureText: true,
            ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                icon: const Icon(Icons.add_link),
                label: const Text('Probe and Connect'),
                onPressed: controller.connecting || controller.scanning
                    ? null
                    : () async {
                        await controller.addManualEndpoint(
                          manualAddress.text,
                          token: token.text,
                        );
                        final endpoint = controller.endpoints.firstOrNull;
                        if (endpoint?.device != null && mounted) {
                          await controller.connect(
                            endpoint!,
                            token: token.text,
                          );
                        }
                      },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _endpointTile(DeviceEndpoint endpoint) {
    final available = endpoint.device != null;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: ListTile(
          leading: Icon(
            available ? Icons.sensors : Icons.warning_amber,
            color: available ? Colors.teal.shade700 : Colors.orange.shade800,
          ),
          title: Text(
            endpoint.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text(
            endpoint.error ?? endpoint.subtitle,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          trailing: FilledButton(
            onPressed: available && !controller.connecting
                ? () => controller.connect(endpoint, token: token.text)
                : null,
            child: const Text('Connect'),
          ),
        ),
      ),
    );
  }

  Widget _connectedScaffold(BuildContext context) {
    final label = controller.device?.device.deviceLabel ?? 'Open Aria';
    return Scaffold(
      appBar: AppBar(
        title: Text(label),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            icon: const Icon(Icons.refresh),
            onPressed: controller.busy ? null : controller.refreshAll,
          ),
          IconButton(
            tooltip: 'Disconnect',
            icon: const Icon(Icons.link_off),
            onPressed: controller.disconnect,
          ),
        ],
      ),
      body: SafeArea(
        child: IndexedStack(
          index: tab,
          children: [
            _controlTab(),
            _sessionsTab(),
            _deviceTab(),
            _networkTab(),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (value) => setState(() => tab = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.videocam), label: 'Control'),
          NavigationDestination(
            icon: Icon(Icons.inventory_2),
            label: 'Sessions',
          ),
          NavigationDestination(icon: Icon(Icons.memory), label: 'Device'),
          NavigationDestination(icon: Icon(Icons.wifi), label: 'Network'),
        ],
      ),
    );
  }

  Widget _controlTab() {
    final capture = controller.capture;
    final snapshot = capture?.snapshot;
    final active = snapshot?.activeRecording;
    final retained = snapshot?.retainedUnsuccessful;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        if (controller.error != null) _ErrorBanner(message: controller.error!),
        _previewCard(),
        const SizedBox(height: 12),
        _stateCard(snapshot, active, retained),
        const SizedBox(height: 12),
        _commandCard(snapshot, active),
        const SizedBox(height: 12),
        _imuCard(snapshot?.runtime.liveImu),
      ],
    );
  }

  Widget _previewCard() {
    final frame = controller.previewFrame;
    return Card(
      child: AspectRatio(
        aspectRatio: 16 / 9,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: frame == null
              ? Container(
                  color: const Color(0xff111827),
                  child: const Center(
                    child: Text(
                      'Waiting for preview',
                      style: TextStyle(color: Colors.white70),
                    ),
                  ),
                )
              : Image.memory(
                  frame.bytes,
                  gaplessPlayback: true,
                  fit: BoxFit.cover,
                ),
        ),
      ),
    );
  }

  Widget _stateCard(
    CaptureSnapshot? snapshot,
    RecordingGeneration? active,
    RecordingGeneration? retained,
  ) {
    final device = controller.device;
    final storage = device?.storage;
    final runtime = snapshot?.runtime ?? device?.runtime;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _StateChip(state: snapshot?.deviceState ?? 'unknown'),
                const Spacer(),
                Text(runtime?.connectionMethod ?? 'unknown'),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                _Metric(
                  label: 'Camera',
                  value: runtime?.cameraState ?? 'unknown',
                ),
                _Metric(
                  label: 'Temp',
                  value:
                      '${runtime?.temperatureCelsius.toStringAsFixed(1) ?? '-'} C',
                ),
                _Metric(
                  label: 'Free',
                  value: formatBytes(storage?.availableBytes ?? 0),
                ),
                _Metric(
                  label: 'Writable',
                  value: storage?.writable == true ? 'yes' : 'no',
                ),
              ],
            ),
            if (active != null) ...[
              const Divider(height: 24),
              Text(
                active.recordingState.displayName,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              Text(
                active.recordingState.sessionId,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 12,
                runSpacing: 8,
                children: [
                  _Metric(
                    label: 'Elapsed',
                    value: formatSeconds(
                      active.recordingState.progress.elapsedSeconds,
                    ),
                  ),
                  _Metric(
                    label: 'Frames',
                    value: '${active.recordingState.progress.capturedFrames}',
                  ),
                  _Metric(
                    label: 'Written',
                    value: formatBytes(
                      active.recordingState.progress.bytesWritten,
                    ),
                  ),
                ],
              ),
            ],
            if (retained != null) ...[
              const Divider(height: 24),
              Text(
                'Retained unsuccessful session',
                style: TextStyle(color: Colors.red.shade800),
              ),
              Text(
                retained.recordingState.sessionId,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
            if (controller.safeSwapAuthorized) ...[
              const Divider(height: 24),
              Row(
                children: [
                  Icon(Icons.check_circle, color: Colors.green.shade700),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text(
                      'Safe swap receipt accepted. The removable volume is released.',
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _commandCard(CaptureSnapshot? snapshot, RecordingGeneration? active) {
    final canStart =
        !controller.busy &&
        snapshot?.deviceState == 'idle' &&
        controller.device?.storage.writable == true &&
        controller.device?.capabilities.capture == true;
    final canStop = !controller.busy && snapshot?.deviceState == 'recording';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: displayName,
              decoration: const InputDecoration(
                labelText: 'Session display name',
                prefixIcon: Icon(Icons.edit),
              ),
              enabled: canStart,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    icon: const Icon(Icons.fiber_manual_record),
                    label: const Text('Start'),
                    onPressed: canStart
                        ? () => controller.startCapture(displayName.text)
                        : null,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    icon: const Icon(Icons.stop),
                    label: const Text('Stop'),
                    onPressed: canStop ? controller.stopCapture : null,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                icon: const Icon(Icons.eject),
                label: const Text('Stop and Request Safe Swap'),
                onPressed: canStop ? controller.requestSafeSwap : null,
              ),
            ),
            if (active != null &&
                active.recordingState.diagnostics.isNotEmpty) ...[
              const Divider(height: 24),
              ...active.recordingState.diagnostics.map(
                (diagnostic) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.error_outline),
                  title: Text(diagnostic.code),
                  subtitle: Text(diagnostic.message),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _imuCard(LiveImu? imu) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('Live IMU'),
            if (imu == null)
              const Text('No live IMU sample in the current snapshot.')
            else
              Wrap(
                spacing: 12,
                runSpacing: 8,
                children: [
                  _Metric(label: 'Sync', value: imu.syncQuality),
                  _Metric(label: 'Accel', value: vectorText(imu.accelerometer)),
                  _Metric(label: 'Gyro', value: vectorText(imu.gyroscope)),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Widget _sessionsTab() {
    final items = controller.sessions.items;
    return RefreshIndicator(
      onRefresh: controller.refreshAll,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _sectionTitle('Sessions'),
          if (items.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 24),
              child: Center(
                child: Text('No sessions reported by this device.'),
              ),
            ),
          ...items.map(
            (session) => Card(
              margin: const EdgeInsets.only(bottom: 10),
              child: ListTile(
                leading: Icon(
                  session.verdict == 'usable'
                      ? Icons.verified
                      : Icons.inventory_2,
                  color: session.verdict == 'usable'
                      ? Colors.green.shade700
                      : Colors.blueGrey,
                ),
                title: Text(
                  session.displayName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  '${session.producerOutcome} / ${session.verdict ?? 'unknown'} / ${formatSeconds(session.durationSeconds)} / ${formatBytes(session.totalBytes)}',
                ),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _showSessionDetail(session),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _deviceTab() {
    final device = controller.device;
    final focus = controller.cameraFocus;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _sectionTitle('Device'),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _InfoRow(
                  label: 'Device ID',
                  value: device?.device.deviceId ?? '-',
                ),
                _InfoRow(label: 'API', value: device?.apiVersion ?? '-'),
                _InfoRow(
                  label: 'Package',
                  value: device?.packageVersion ?? '-',
                ),
                _InfoRow(label: 'Commit', value: device?.buildCommit ?? '-'),
                _InfoRow(
                  label: 'Volume',
                  value: device?.storage.volumeId ?? '-',
                ),
                _InfoRow(
                  label: 'Capacity',
                  value: formatBytes(device?.storage.totalBytes ?? 0),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _sectionTitle('Camera Focus'),
                if (focus == null)
                  const Text('Focus controls are not exposed by this device.')
                else ...[
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Autofocus'),
                    value: focus.autoEnabled == true,
                    onChanged: focus.autoSupported && !controller.busy
                        ? (enabled) => controller.setCameraAutofocus(enabled)
                        : null,
                  ),
                  Slider(
                    value: focus.value.clamp(focus.minimum, focus.maximum),
                    min: focus.minimum,
                    max: focus.maximum,
                    divisions: focus.step > 0
                        ? ((focus.maximum - focus.minimum) / focus.step).round()
                        : null,
                    label: focus.value.toStringAsFixed(0),
                    onChanged: focus.autoEnabled == true || controller.busy
                        ? null
                        : (value) => controller.setCameraFocus(value),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _networkTab() {
    final network = controller.network;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _sectionTitle('Network'),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (network == null)
                  const Text('Network status is not exposed by this device.')
                else ...[
                  Wrap(
                    spacing: 12,
                    runSpacing: 8,
                    children: [
                      _Metric(label: 'Mode', value: network.mode),
                      _Metric(
                        label: 'Verified',
                        value: network.verified ? 'yes' : 'no',
                      ),
                      _Metric(label: 'Route', value: network.defaultRoute),
                      _Metric(
                        label: 'Mutation',
                        value: network.mutationEnabled
                            ? 'enabled'
                            : (network.disabledReason ?? 'disabled'),
                      ),
                    ],
                  ),
                  const Divider(height: 24),
                  _interfaceLine(network.ap),
                  _interfaceLine(network.wifiClient),
                  _interfaceLine(network.wired),
                ],
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        icon: const Icon(Icons.wifi_tethering),
                        label: const Text('Hotspot'),
                        onPressed:
                            network?.mutationEnabled == true && !controller.busy
                            ? controller.setHotspotMode
                            : null,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton.icon(
                        icon: const Icon(Icons.lan),
                        label: const Text('Ethernet DHCP'),
                        onPressed:
                            network?.mutationEnabled == true && !controller.busy
                            ? controller.setEthernetDhcp
                            : null,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: wifiPassphrase,
                  decoration: const InputDecoration(
                    labelText: 'Wi-Fi passphrase for selected network',
                    prefixIcon: Icon(Icons.password),
                  ),
                  obscureText: true,
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    icon: const Icon(Icons.manage_search),
                    label: const Text('Scan Wi-Fi'),
                    onPressed:
                        network?.mutationEnabled == true && !controller.busy
                        ? controller.scanNetworks
                        : null,
                  ),
                ),
                const SizedBox(height: 8),
                ...controller.networkScan.map(
                  (entry) => ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.wifi),
                    title: Text(entry.ssid ?? '<hidden>'),
                    subtitle: Text(
                      '${entry.security} / ${entry.signalDbm} dBm',
                    ),
                    trailing: TextButton(
                      onPressed: !controller.busy
                          ? () =>
                                controller.joinWifi(entry, wifiPassphrase.text)
                          : null,
                      child: const Text('Join'),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  void _showSessionDetail(SessionSummary session) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) {
        return DraggableScrollableSheet(
          expand: false,
          initialChildSize: 0.75,
          minChildSize: 0.4,
          maxChildSize: 0.95,
          builder: (context, scrollController) {
            return FutureBuilder<SessionDetailView>(
              future: controller.loadSessionDetail(session.sessionId),
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.hasError) {
                  return ListView(
                    controller: scrollController,
                    padding: const EdgeInsets.all(16),
                    children: [
                      _ErrorBanner(message: snapshot.error.toString()),
                    ],
                  );
                }
                final detail = snapshot.requireData;
                return ListView(
                  controller: scrollController,
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  children: [
                    _sectionTitle(detail.displayName),
                    _InfoRow(label: 'Session', value: detail.sessionId),
                    _InfoRow(label: 'Manifest', value: detail.manifestId),
                    _InfoRow(label: 'Device', value: detail.deviceLabel),
                    _InfoRow(label: 'Mode', value: detail.captureMode),
                    _InfoRow(
                      label: 'Duration',
                      value: formatSeconds(detail.durationSeconds),
                    ),
                    _InfoRow(
                      label: 'Sealed',
                      value: detail.sealed ? 'yes' : 'no',
                    ),
                    const SizedBox(height: 16),
                    _sectionTitle('Artifacts'),
                    if (detail.artifacts.isEmpty)
                      const Text(
                        'No artifacts are exposed in this session detail.',
                      )
                    else
                      ...detail.artifacts.map(
                        (artifact) => Card(
                          margin: const EdgeInsets.only(bottom: 8),
                          child: ListTile(
                            leading: const Icon(Icons.description),
                            title: Text(
                              artifact.path,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(
                              '${artifact.role} / ${artifact.mediaType} / ${formatBytes(artifact.bytes)}',
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                            trailing: IconButton(
                              tooltip: 'Copy range URL',
                              icon: const Icon(Icons.link),
                              onPressed: () {
                                final uri = controller.artifactUri(
                                  detail.sessionId,
                                  artifact.artifactId,
                                );
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(
                                    content: Text(
                                      uri?.toString() ?? artifact.artifactId,
                                    ),
                                  ),
                                );
                              },
                            ),
                          ),
                        ),
                      ),
                  ],
                );
              },
            );
          },
        );
      },
    );
  }

  Widget _interfaceLine(NetworkInterfaceView item) {
    final detail = [
      item.peerOrSsid,
      if (item.addresses.isNotEmpty) item.addresses.join(', '),
    ].whereType<String>().where((value) => value.isNotEmpty).join(' / ');
    return ListTile(
      contentPadding: EdgeInsets.zero,
      dense: true,
      title: Text('${item.name}: ${item.state}'),
      subtitle: detail.isEmpty ? null : Text(detail),
    );
  }

  Widget _sectionTitle(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        text,
        style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(minWidth: 96),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label, style: Theme.of(context).textTheme.labelMedium),
          Text(value, style: const TextStyle(fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}

class _StateChip extends StatelessWidget {
  const _StateChip({required this.state});

  final String state;

  @override
  Widget build(BuildContext context) {
    final color = switch (state) {
      'recording' => Colors.red.shade700,
      'idle' => Colors.green.shade700,
      'finalizing' || 'encoding' || 'verifying' => Colors.orange.shade800,
      _ => Colors.blueGrey.shade700,
    };
    return Chip(
      avatar: Icon(Icons.circle, size: 12, color: color),
      label: Text(state),
      side: BorderSide(color: color),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 92,
            child: Text(label, style: Theme.of(context).textTheme.labelMedium),
          ),
          Expanded(
            child: Text(value, overflow: TextOverflow.ellipsis, maxLines: 2),
          ),
        ],
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Material(
        color: Colors.red.shade50,
        borderRadius: BorderRadius.circular(8),
        child: ListTile(
          leading: Icon(Icons.error_outline, color: Colors.red.shade800),
          title: Text(message, style: TextStyle(color: Colors.red.shade900)),
        ),
      ),
    );
  }
}

String formatBytes(int bytes) {
  if (bytes <= 0) {
    return '0 B';
  }
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  var value = bytes.toDouble();
  var index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return '${value.toStringAsFixed(index == 0 ? 0 : 1)} ${units[index]}';
}

String formatSeconds(double seconds) {
  final total = seconds.round();
  final minutes = total ~/ 60;
  final remainder = total % 60;
  return '$minutes:${remainder.toString().padLeft(2, '0')}';
}

String vectorText(Vector3 vector) {
  return '${vector.x.toStringAsFixed(0)}, ${vector.y.toStringAsFixed(0)}, ${vector.z.toStringAsFixed(0)}';
}
