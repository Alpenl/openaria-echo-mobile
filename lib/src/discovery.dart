import 'dart:async';
import 'dart:io';

import 'package:multicast_dns/multicast_dns.dart';

import 'device_api.dart';
import 'models.dart';

const openAriaMdnsServices = <String>[
  '_ylx-capture._tcp.local',
  '_http._tcp.local',
];

class DeviceDiscoveryService {
  const DeviceDiscoveryService({
    this.probeTimeout = const Duration(seconds: 2),
  });

  final Duration probeTimeout;

  Future<List<DeviceEndpoint>> discover() async {
    final candidates = <String, DeviceEndpoint>{};
    final client = MDnsClient();
    await client.start();
    try {
      for (final service in openAriaMdnsServices) {
        await _discoverService(client, service, candidates);
      }
    } finally {
      client.stop();
    }

    final probed = await Future.wait(candidates.values.map(_probeEndpoint));
    probed.sort((a, b) {
      final byLabel = a.title.compareTo(b.title);
      return byLabel == 0
          ? a.baseUri.toString().compareTo(b.baseUri.toString())
          : byLabel;
    });
    return probed;
  }

  Future<void> _discoverService(
    MDnsClient client,
    String service,
    Map<String, DeviceEndpoint> candidates,
  ) async {
    await for (final ptr in client.lookup<PtrResourceRecord>(
      ResourceRecordQuery.serverPointer(service),
      timeout: const Duration(seconds: 2),
    )) {
      await for (final srv in client.lookup<SrvResourceRecord>(
        ResourceRecordQuery.service(ptr.domainName),
        timeout: const Duration(seconds: 2),
      )) {
        final addresses = await _addressesFor(client, srv.target);
        final hosts = addresses.isEmpty ? [srv.target] : addresses;
        for (final host in hosts) {
          final uri = Uri(scheme: 'http', host: host, port: srv.port);
          candidates['${uri.host}:${uri.port}'] = DeviceEndpoint(
            baseUri: uri,
            source: 'mDNS',
            host: host,
            port: srv.port,
            serviceName: service,
          );
        }
      }
    }
  }

  Future<List<String>> _addressesFor(MDnsClient client, String host) async {
    final addresses = <String>{};
    await for (final record in client.lookup<IPAddressResourceRecord>(
      ResourceRecordQuery.addressIPv4(host),
      timeout: const Duration(seconds: 1),
    )) {
      addresses.add(record.address.address);
    }
    await for (final record in client.lookup<IPAddressResourceRecord>(
      ResourceRecordQuery.addressIPv6(host),
      timeout: const Duration(seconds: 1),
    )) {
      addresses.add(record.address.address);
    }
    return addresses.toList(growable: false);
  }

  Future<DeviceEndpoint> endpointFromManualAddress(
    String input, {
    String? token,
  }) async {
    final uri = normalizeDeviceBaseUri(input);
    return _probeEndpoint(
      DeviceEndpoint(
        baseUri: uri,
        source: 'manual',
        host: uri.host,
        port: uri.hasPort ? uri.port : (uri.scheme == 'https' ? 443 : 80),
      ),
      token: token,
    );
  }

  Future<DeviceEndpoint> _probeEndpoint(
    DeviceEndpoint endpoint, {
    String? token,
  }) async {
    final client = DeviceApiClient(
      baseUri: endpoint.baseUri,
      accessToken: token,
    );
    try {
      final device = await client.getDevice().timeout(probeTimeout);
      return endpoint.copyWith(device: device);
    } on SocketException catch (error) {
      return endpoint.copyWith(error: error.message);
    } on TimeoutException {
      return endpoint.copyWith(error: 'Probe timed out');
    } catch (error) {
      return endpoint.copyWith(error: formatDeviceApiError(error));
    } finally {
      client.close();
    }
  }
}
