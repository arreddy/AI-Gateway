package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/go-redis/redis/v8"
	_ "github.com/lib/pq"
	"go.uber.org/zap"
	"google.golang.org/grpc"

	a2aapi "astra-gateway/backend/a2a-service/internal/api"
	a2amessaging "astra-gateway/backend/a2a-service/internal/messaging"
	a2aregistry "astra-gateway/backend/a2a-service/internal/registry"
)

func main() {
	// Initialize logger
	logger, _ := zap.NewProduction()
	defer logger.Sync()

	// Initialize Redis
	redisClient := redis.NewClient(&redis.Options{
		Addr: getEnv("A2A_REDIS_URL", "redis:6379"),
	})

	// Test Redis connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := redisClient.Ping(ctx).Err(); err != nil {
		logger.Fatal("Redis connection failed", zap.Error(err))
	}

	// Initialize registry
	registry := a2aregistry.NewAgentRegistry(redisClient, logger)

	// Initialize message broker
	kafkaBrokers := []string{getEnv("A2A_KAFKA_BROKERS", "kafka:29092")}
	broker, err := a2amessaging.NewMessageBroker(kafkaBrokers, logger)
	if err != nil {
		logger.Fatal("Failed to create message broker", zap.Error(err))
	}

	// Initialize HTTP server
	gin.SetMode(gin.ReleaseMode)
	router := gin.Default()

	// Create API handler
	apiHandler := a2aapi.NewAPIHandler(registry, broker, logger)
	apiHandler.RegisterRoutes(router)

	httpServer := &http.Server{
		Addr:         ":" + getEnv("A2A_PORT", "8082"),
		Handler:      router,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
	}

	// Initialize gRPC server (optional, for high-performance inter-service communication)
	grpcPort := getEnv("A2A_GRPC_PORT", "50051")
	grpcListener, err := net.Listen("tcp", ":"+grpcPort)
	if err != nil {
		logger.Fatal("Failed to create gRPC listener", zap.Error(err))
	}

	grpcServer := grpc.NewServer()
	// Register gRPC services here
	// pb.RegisterA2AServiceServer(grpcServer, &grpcHandler)

	// Start HTTP server
	go func() {
		logger.Info("Starting HTTP server", zap.String("addr", httpServer.Addr))
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("HTTP server error", zap.Error(err))
		}
	}()

	// Start gRPC server
	go func() {
		logger.Info("Starting gRPC server", zap.String("addr", grpcListener.Addr().String()))
		if err := grpcServer.Serve(grpcListener); err != nil {
			logger.Fatal("gRPC server error", zap.Error(err))
		}
	}()

	// Health check endpoint
	go healthCheck(registry, logger)

	// Graceful shutdown
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	logger.Info("Shutting down A2A service")

	// Shutdown HTTP server
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer shutdownCancel()

	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		logger.Error("HTTP server shutdown error", zap.Error(err))
	}

	// Shutdown gRPC server
	grpcServer.GracefulStop()

	// Cleanup
	broker.Close()
	redisClient.Close()

	logger.Info("A2A service shut down successfully")
}

// Health check loop - periodically validate registered agents
func healthCheck(registry *a2aregistry.AgentRegistry, logger *zap.Logger) {
	ticker := time.NewTicker(time.Duration(getEnvAsInt("A2A_HEALTH_CHECK_INTERVAL_SECS", 10)) * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		agents, err := registry.GetAllAgents(context.Background())
		if err != nil {
			logger.Error("Failed to get agents for health check", zap.Error(err))
			continue
		}

		for _, agent := range agents {
			// Check agent health by pinging its endpoint
			if err := registry.HealthCheck(context.Background(), agent.ID); err != nil {
				logger.Warn("Agent health check failed", zap.String("agent_id", agent.ID), zap.Error(err))
				// Optionally unregister unhealthy agents
			}
		}
	}
}

func getEnv(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func getEnvAsInt(key string, fallback int) int {
	valueStr := getEnv(key, "")
	if valueStr == "" {
		return fallback
	}
	var value int
	_, err := fmt.Sscanf(valueStr, "%d", &value)
	if err != nil {
		return fallback
	}
	return value
}
