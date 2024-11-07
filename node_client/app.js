const grpc = require('@grpc/grpc-js');
const { v4: uuidv4 } = require('uuid');
const {
  XlmEcosystemServiceClient
} = require('./xlm_eco_api_grpc_pb');
const {
  ClientRegistrationRequest,
  ClientUnregistrationRequest,
  ProviderRequest,
  ProviderSelectionRequest,
  ChatRequest,
  EmbeddingRequest,
  EmptyRequest
} = require('./xlm_eco_api_pb');

// Process command-line arguments
const args = require('minimist')(process.argv.slice(2));
const host = args.host || '127.0.0.1';
const port = args.port || 50051;
const provider = args.provider;
const modelName = args.model_name;
const prompt = args.prompt;

if (!provider || !modelName || !prompt) {
  console.log('Usage: node your_script.js --host <host> --port <port> --provider <provider> --model_name <model_name> --prompt <prompt>');
  process.exit(1);
}

async function runSetup(host, port, provider, modelName, prompt) {
  // Establish the channel to communicate with the gRPC server
  const client = new XlmEcosystemServiceClient(`${host}:${port}`, grpc.credentials.createInsecure());

  // Register the client
  const clientId = uuidv4();
  const clientName = "nodejs-client-1";
  console.log(`Registering client with name: ${clientName}, ID: ${clientId}`);

  const clientRegistrationRequest = new ClientRegistrationRequest();
  clientRegistrationRequest.setClientName(clientName);
  clientRegistrationRequest.setClientId(clientId);

  client.registerClient(clientRegistrationRequest, (err, response) => {
    if (err || !response.getSuccess()) {
      console.error("Failed to register the client! Aborting...");
      return;
    }
    console.log("Successfully registered the client!");

    // List providers and their capabilities
    const emptyRequest = new EmptyRequest();
    client.listProviders(emptyRequest, (err, listResponse) => {
      if (err) {
        console.error("Error listing providers:", err);
        return;
      }

      listResponse.getProvidersList().forEach(providerInfo => {
        console.log(`Provider: ${providerInfo.getProviderName()}, Service Level: ${providerInfo.getServiceLevel()}`);
        const capabilities = providerInfo.getCapabilitiesMap();
        capabilities.forEach((supported, capability) => {
          console.log(`  Capability: ${capability} -> ${supported}`);
        });
      });

      // Get provider capabilities
      console.log("Now testing getProviderCapabilities...");
      const providerRequest = new ProviderRequest();
      providerRequest.setClientId(clientId);
      providerRequest.setProvider(provider);

      client.getProviderCapabilities(providerRequest, (err, providerCapabilities) => {
        if (err) {
          console.error("Error getting provider capabilities:", err);
          return;
        }
        console.log(`Provider: ${providerCapabilities.getProviderName()}, Service Level: ${providerCapabilities.getServiceLevel()}`);
        const capabilities = providerCapabilities.getCapabilitiesMap();
        capabilities.forEach((supported, capability) => {
          console.log(`  Capability: ${capability} -> ${supported}`);
        });

        // Set preferred provider capabilities
        console.log("Now testing setProvider...");
        const providerSelectionRequest = new ProviderSelectionRequest();
        providerSelectionRequest.setClientId(clientId);
        providerSelectionRequest.getProviderCapabilitiesMap().set(provider, {
          capabilities: ["chat", "embedding"]
        });

        client.setPreferredProviders(providerSelectionRequest, (err, selectionResponse) => {
          if (err || !selectionResponse.getSuccess()) {
            console.error("Failed to set the provider! Aborting...");
            return;
          }
          console.log(`Successfully set the provider to: ${provider}`);

          // Prepare a request message with provider, model name, and prompt
          const chatRequest = new ChatRequest();
          chatRequest.setClientId(clientId);
          chatRequest.setPrompt(prompt);
          chatRequest.setProvider(provider);
          chatRequest.setModelName(modelName);

          // Call the syncChat method from the server and get the response
          client.syncChat(chatRequest, (err, response) => {
            if (err) {
              console.error("Error in syncChat:", err);
              return;
            }
            console.log("Response from syncChat:");
            console.log(response.getCompletion());

            // Call the asyncChat method and get the response iterator
            console.log("\nNow, let's test the asyncChat...");
            const call = client.asyncChat(chatRequest);
            call.on('data', responsePart => {
              process.stdout.write(responsePart.getToken());
            });
            call.on('end', () => {
              console.log("\nStreaming completed.");
            });
            call.on('error', err => {
              console.error("\nError occurred:", err.message);
            });

            // Test embedding capability
            if (capabilities.get("embedding")) {
              const embeddingRequest = new EmbeddingRequest();
              embeddingRequest.setClientId(clientId);
              embeddingRequest.setText("Mickey Mouse is a Disney cartoon character.");

              client.getEmbedding(embeddingRequest, (err, embeddingResponse) => {
                if (err) {
                  console.error("Error getting embedding:", err);
                  return;
                }
                console.log("Embedding returned:");
                console.log(embeddingResponse.getEmbeddingList().join(" "));
              });
            } else {
              console.log(`Provider ${provider} does not support the embedding capability.`);
            }

            // Unregister the client
            console.log("Unregistering the client...");
            const clientUnregistrationRequest = new ClientUnregistrationRequest();
            clientUnregistrationRequest.setClientId(clientId);

            client.unregisterClient(clientUnregistrationRequest, (err, unregisterResponse) => {
              if (err || !unregisterResponse.getSuccess()) {
                console.error("Failed to unregister the client.");
              } else {
                console.log("Successfully unregistered the client.");
              }
            });
          });
        });
      });
    });
  });
}


runSetup(host, port, provider, modelName, prompt);
