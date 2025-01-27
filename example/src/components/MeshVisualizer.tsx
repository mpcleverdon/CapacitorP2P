import React, { useEffect, useRef, useState } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import { Box, Text, Paper, Group, Stack, useMantineTheme } from '@mantine/core';
import { IconWifi, IconWifiOff } from '@tabler/icons-react';
import type { NetworkStats as PluginNetworkStats } from 'capacitor-p2p-counter';

interface Node {
  id: string;
  strength: number;
  connections: number;
  lastSeen: number;
}

interface GraphNode extends Node {
  x?: number;
  y?: number;
}

interface GraphLink {
  source: GraphNode;
  target: GraphNode;
  strength: number;
}

export interface NetworkStats extends PluginNetworkStats {
  totalPeers: number;
}

interface Message {
  id: string;
  path: string[];
  progress: number;
  timestamp: number;
  status?: 'pending' | 'success' | 'failed';
  attempts?: number;
  error?: string;
}

interface MeshVisualizerProps {
  topology: any;
  networkStats: NetworkStats | null | undefined;
  activeMessages?: any[];
  width?: number;
  height?: number;
}

export function MeshVisualizer({ 
  topology, 
  networkStats, 
  activeMessages = [], 
  width = 600, 
  height = 400 
}: MeshVisualizerProps) {
  const theme = useMantineTheme();
  const graphRef = useRef<any>();
  const [hoveredNode, setHoveredNode] = useState<GraphNode | null>(null);
  const [mousePosition, setMousePosition] = useState({ x: 0, y: 0 });
  const [highlightLinks, setHighlightLinks] = useState(new Set<string>());
  const [messageParticles, setMessageParticles] = useState<Message[]>([]);

  const graphData = React.useMemo(() => {
    const nodes: GraphNode[] = [
      {
        id: topology.localDeviceId,
        strength: 1,
        connections: topology.discoveredPeers.length,
        lastSeen: Date.now()
      },
      ...topology.discoveredPeers.map((peer: {
        deviceId: string;
        networkStrength: number;
        connectionCount: number;
        lastSeen: number;
        connectedPeers?: string[];
      }) => ({
        id: peer.deviceId,
        strength: peer.networkStrength,
        connections: peer.connectionCount,
        lastSeen: peer.lastSeen
      }))
    ];

    const links: GraphLink[] = topology.discoveredPeers.map((peer: {
      deviceId: string;
      networkStrength: number;
      connectedPeers?: string[];
    }) => ({
      source: nodes.find(node => node.id === topology.localDeviceId)!,
      target: nodes.find(node => node.id === peer.deviceId)!,
      strength: peer.networkStrength
    }));

    // Add peer-to-peer connections
    topology.discoveredPeers.forEach((peer: {
      deviceId: string;
      connectedPeers?: string[];
    }) => {
      peer.connectedPeers?.forEach((connectedPeerId: string) => {
        if (connectedPeerId !== topology.localDeviceId) {
          links.push({
            source: nodes.find(node => node.id === peer.deviceId)!,
            target: nodes.find(node => node.id === connectedPeerId)!,
            strength: 0.5 // Default strength for indirect connections
          });
        }
      });
    });

    return { nodes, links };
  }, [topology]);

  useEffect(() => {
    if (graphRef.current) {
      graphRef.current.d3Force('charge').strength(-100);
      graphRef.current.d3Force('link').distance(100);
    }
  }, []);

  const getNodeColor = (node: GraphNode) => {
    const now = Date.now();
    const age = now - node.lastSeen;
    
    if (node.id === topology.localDeviceId) {
      return theme.colors.blue[6];
    }
    
    if (age > 30000) { // Stale node
      return theme.colors.gray[5];
    }
    
    const strength = node.strength;
    if (strength > 0.7) return theme.colors.green[6];
    if (strength > 0.4) return theme.colors.yellow[6];
    return theme.colors.red[6];
  };

  const getLinkColor = (link: GraphLink) => {
    const strength = link.strength;
    if (strength > 0.7) return theme.colors.green[3];
    if (strength > 0.4) return theme.colors.yellow[3];
    return theme.colors.red[3];
  };

  // Add hover handling
  const handleNodeHover = (node: GraphNode | null) => {
    setHoveredNode(node);
  };

  const handleCanvasMouseMove = (event: React.MouseEvent) => {
    setMousePosition({ x: event.clientX, y: event.clientY });
  };

  const formatTimestamp = (timestamp: number) => {
    const seconds = Math.floor((Date.now() - timestamp) / 1000);
    if (seconds < 60) return `${seconds}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    return `${Math.floor(seconds / 3600)}h ago`;
  };

  const getConnectionQuality = (strength: number) => {
    if (strength > 0.7) return 'Excellent';
    if (strength > 0.4) return 'Good';
    if (strength > 0.2) return 'Fair';
    return 'Poor';
  };

  // Add message animation frame handling
  useEffect(() => {
    let animationFrameId: number;
    const animate = () => {
      setMessageParticles(prev => 
        prev.filter(msg => Date.now() - msg.timestamp < 2000).map(msg => ({
          ...msg,
          progress: Math.min(1, (Date.now() - msg.timestamp) / 1000)
        }))
      );
      animationFrameId = requestAnimationFrame(animate);
    };
    
    animate();
    return () => cancelAnimationFrame(animationFrameId);
  }, []);

  // Update message particles when new messages arrive
  useEffect(() => {
    setMessageParticles(prev => [...prev, ...activeMessages]);
  }, [activeMessages]);

  const getLinkId = (source: string, target: string) => 
    `${source}-${target}`;

  const getQualityIndicator = (strength: number) => {
    const bars = [0.25, 0.5, 0.75, 1].map(threshold => ({
      active: strength >= threshold,
      height: 6 + threshold * 6
    }));
    return bars;
  };

  // Add message status colors
  const getMessageColor = (status?: string) => {
    switch (status) {
      case 'success': return theme.colors.green[5];
      case 'failed': return theme.colors.red[5];
      default: return theme.colors.blue[4];
    }
  };

  // Add message status indicator
  const drawMessageStatus = (ctx: CanvasRenderingContext2D, x: number, y: number, status?: string) => {
    const size = 4;
    ctx.beginPath();
    
    if (status === 'success') {
      // Draw checkmark
      ctx.moveTo(x - size, y);
      ctx.lineTo(x - size/3, y + size/1.5);
      ctx.lineTo(x + size, y - size);
      ctx.strokeStyle = theme.colors.green[5];
      ctx.lineWidth = 2;
      ctx.stroke();
    } else if (status === 'failed') {
      // Draw X
      ctx.moveTo(x - size, y - size);
      ctx.lineTo(x + size, y + size);
      ctx.moveTo(x + size, y - size);
      ctx.lineTo(x - size, y + size);
      ctx.strokeStyle = theme.colors.red[5];
      ctx.lineWidth = 2;
      ctx.stroke();
    }
  };

  const getBackgroundColor = (opacity: number) => {
    const [r, g, b] = theme.colors.dark[7]
      .match(/\d+/g)!
      .map(Number);
    return `rgba(${r}, ${g}, ${b}, ${opacity})`;
  };

  return (
    <Box>
      <Text size="sm" mb="xs">Network Topology</Text>
      
      {/* Network Statistics Overlay */}
      <Paper 
        shadow="sm" 
        p="md" 
        style={{ 
          position: 'absolute', 
          top: 10, 
          right: 10, 
          zIndex: 1000,
          backgroundColor: getBackgroundColor(0.8)
        }}
      >
        <Stack gap="xs">
          <Group gap="xs">
            {networkStats?.networkStrength && networkStats.networkStrength > 0.3 ? (
              <IconWifi size={18} color={theme.colors.blue[4]} />
            ) : (
              <IconWifiOff size={18} color={theme.colors.red[4]} />
            )}
            <Text size="sm" color="dimmed">Network Status</Text>
          </Group>
          <Text size="sm">Peers: {networkStats?.totalPeers || topology.discoveredPeers.length}</Text>
          <Text size="sm">Latency: {networkStats?.averageLatency?.toFixed(0) || '---'} ms</Text>
          <Text size="sm">Packet Loss: {(networkStats?.packetLoss || 0).toFixed(1)}%</Text>
          <Text size="sm">Messages: {networkStats?.messageCount || 0}</Text>
          <Text size="sm">Strength: {((networkStats?.networkStrength || 0) * 100).toFixed(0)}%</Text>
        </Stack>
      </Paper>

      {/* Peer Information Tooltip */}
      {hoveredNode && (
        <Paper
          shadow="md"
          p="sm"
          style={{
            position: 'fixed',
            left: mousePosition.x + 10,
            top: mousePosition.y + 10,
            zIndex: 1000,
            pointerEvents: 'none',
            backgroundColor: getBackgroundColor(0.9)
          }}
        >
          <Stack gap="xs">
            <Text size="sm" fw={500}>Device ID: {hoveredNode.id}</Text>
            <Text size="sm">Connections: {hoveredNode.connections}</Text>
            <Text size="sm">
              Quality: {getConnectionQuality(hoveredNode.strength)}
            </Text>
            <Text size="sm">
              Last Seen: {formatTimestamp(hoveredNode.lastSeen)}
            </Text>
            <Text size="sm">
              Signal Strength: {(hoveredNode.strength * 100).toFixed(0)}%
            </Text>
          </Stack>
        </Paper>
      )}

      <ForceGraph2D
        ref={graphRef}
        graphData={graphData}
        width={width}
        height={height}
        nodeRelSize={8}
        nodeColor={getNodeColor}
        linkWidth={2}
        linkDirectionalParticles={2}
        linkDirectionalParticleSpeed={0.005}
        onNodeHover={(node) => {
          handleNodeHover(node);
          
          // Highlight connected links
          const newHighlightLinks = new Set<string>();
          if (node) {
            graphData.links.forEach((link: GraphLink) => {
              if (link.source.id === node.id || link.target.id === node.id) {
                newHighlightLinks.add(getLinkId(link.source.id, link.target.id));
              }
            });
          }
          setHighlightLinks(newHighlightLinks);
        }}
        onBackgroundClick={() => setHoveredNode(null)}
        onNodeClick={(node: GraphNode) => {
          // Center view on node
          graphRef.current.centerAt(node.x, node.y, 1000);
          graphRef.current.zoom(2.5, 1000);
        }}
        nodeCanvasObject={(node: GraphNode, ctx, globalScale) => {
          const size = 5;
          const x = node.x!;
          const y = node.y!;
          
          // Draw node circle
          ctx.beginPath();
          ctx.arc(x, y, size, 0, 2 * Math.PI);
          ctx.fillStyle = getNodeColor(node);
          ctx.fill();

          // Draw quality indicator bars
          const bars = getQualityIndicator(node.strength);
          const barWidth = 2;
          const barGap = 1;
          const totalWidth = bars.length * (barWidth + barGap) - barGap;
          const startX = x - totalWidth / 2;

          bars.forEach((bar, i) => {
            ctx.beginPath();
            ctx.rect(
              startX + i * (barWidth + barGap),
              y + size + 2,
              barWidth,
              bar.height
            );
            ctx.fillStyle = bar.active 
              ? theme.colors.green[bar.active ? 6 : 3]
              : theme.colors.gray[5];
            ctx.fill();
          });

          // Draw label
          const label = `${node.id.slice(-4)}`;
          const fontSize = 12/globalScale;
          ctx.font = `${fontSize}px Sans-Serif`;
          ctx.fillStyle = theme.colors.gray[7];
          ctx.textAlign = 'center';
          ctx.fillText(label, x, y + size + 16);
        }}
        linkCanvasObject={(link: GraphLink, ctx: CanvasRenderingContext2D) => {
          const id = getLinkId(link.source.id, link.target.id);
          const isHighlighted = highlightLinks.has(id);
          
          // Draw base link
          ctx.beginPath();
          ctx.strokeStyle = isHighlighted 
            ? theme.colors.blue[5] 
            : getLinkColor(link);
          ctx.lineWidth = isHighlighted ? 4 : 2;
          ctx.moveTo(link.source.x!, link.source.y!);
          ctx.lineTo(link.target.x!, link.target.y!);
          ctx.stroke();

          // Draw message particles with status
          messageParticles
            .filter(msg => {
              const pathStr = msg.path.join('-');
              return pathStr.includes(id);
            })
            .forEach(msg => {
              const pathIndex = msg.path.indexOf(link.source.id);
              if (pathIndex >= 0 && pathIndex < msg.path.length - 1) {
                const segmentProgress = (msg.progress * msg.path.length) - pathIndex;
                if (segmentProgress >= 0 && segmentProgress <= 1) {
                  const x = link.source.x! + (link.target.x! - link.source.x!) * segmentProgress;
                  const y = link.source.y! + (link.target.y! - link.source.y!) * segmentProgress;
                  
                  // Draw message particle
                  ctx.beginPath();
                  ctx.arc(x, y, 4, 0, 2 * Math.PI);
                  ctx.fillStyle = getMessageColor(msg.status);
                  ctx.fill();

                  // Draw status indicator if message completed or failed
                  if (msg.status === 'success' || msg.status === 'failed') {
                    drawMessageStatus(ctx, x, y - 8, msg.status);
                  }

                  // Draw retry count if failed
                  if (msg.status === 'failed' && msg.attempts && msg.attempts > 1) {
                    ctx.font = '10px Sans-Serif';
                    ctx.fillStyle = theme.colors.red[5];
                    ctx.textAlign = 'center';
                    ctx.fillText(`${msg.attempts}x`, x, y + 12);
                  }
                }
              }
            });
        }}
      />
      <Box onMouseMove={handleCanvasMouseMove} style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, pointerEvents: 'none' }} />

      {/* Add message status legend */}
      <Paper
        shadow="sm"
        p="md"
        style={{
          position: 'absolute',
          bottom: 10,
          right: 10,
          zIndex: 1000,
          backgroundColor: getBackgroundColor(0.8)
        }}
      >
        <Stack gap="xs">
          <Text size="sm" fw={500}>Message Status</Text>
          <Group gap="md">
            <Group gap="xs">
              <Box
                style={{
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  backgroundColor: theme.colors.blue[4]
                }}
              />
              <Text size="xs">In Progress</Text>
            </Group>
            <Group gap="xs">
              <Box
                style={{
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  backgroundColor: theme.colors.green[5]
                }}
              />
              <Text size="xs">Success</Text>
            </Group>
            <Group gap="xs">
              <Box
                style={{
                  width: 8,
                  height: 8,
                  borderRadius: '50%',
                  backgroundColor: theme.colors.red[5]
                }}
              />
              <Text size="xs">Failed</Text>
            </Group>
          </Group>
        </Stack>
      </Paper>
    </Box>
  );
} 