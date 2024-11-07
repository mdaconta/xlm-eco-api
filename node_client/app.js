// Import required modules
const grpc = require("@grpc/grpc-js");
const protoLoader = require("@grpc/proto-loader");
const path = require("path");
const { v4: uuidv4 } = require("uuid");

// Load protobuf
const PROTO_PATH = path.join(
  __dirname,
  "..",
  "src",
  "main",
  "proto",
  "xlm-eco-api.proto"
);
const packageDefinition = protoLoader.loadSync(PROTO_PATH, {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true,
});
const xlmEcoProto = grpc.loadPackageDefinition(packageDefinition); //.xlm_eco_api;

// Function to run the client
async function runClient(host, port, provider, modelName, prompt) {
  // Establish the channel to communicate with the gRPC server
  const client = new xlmEcoProto.XlmEcosystemService(
    `${host}:${port}`,
    grpc.credentials.createInsecure()
  );

  // Register the client
  const clientId = uuidv4();
  const clientName = "node-client-1";
  console.log(`Registering client with name: ${clientName}, ID: ${clientId}`);

  const clientRegistrationRequest = {
    client_name: clientName,
    client_id: clientId,
  };

  client.registerClient(
    clientRegistrationRequest,
    (err, registrationResponse) => {
      if (err || !registrationResponse.success) {
        console.log("Error Message: ", err);
        console.log("Restration Response: ", registrationResponse);
        console.error("Failed to register the client! Aborting...");
        return;
      }
      console.log("Successfully registered the client!");

      // List providers and their capabilities
      client.listProviders({}, (err, listResponse) => {
        if (err) return console.error("Error listing providers:", err);
        listResponse.providers.forEach((providerInfo) => {
          console.log(
            `Provider: ${providerInfo.provider_name}, Service Level: ${providerInfo.service_level}`
          );
          for (const [capability, supported] of Object.entries(
            providerInfo.capabilities
          )) {
            console.log(`  Capability: ${capability} -> ${supported}`);
          }
        });

        // Get provider capabilities
        console.log("Now testing getProviderCapabilities...");
        const providerRequest = { client_id: clientId, provider };
        client.getProviderCapabilities(
          providerRequest,
          (err, providerCapabilities) => {
            if (err)
              return console.error("Error getting provider capabilities:", err);
            console.log(
              `Provider: ${providerCapabilities.provider_name}, Service Level: ${providerCapabilities.service_level}`
            );
            for (const [capability, supported] of Object.entries(
              providerCapabilities.capabilities
            )) {
              console.log(`  Capability: ${capability} -> ${supported}`);
            }

            // Set preferred provider capabilities
            console.log("Now testing setProvider...");
            const providerSelectionRequest = {
              client_id: clientId,
              provider_capabilities: {
                [provider]: { capabilities: ["chat", "embedding"] },
              },
            };
            client.setPreferredProviders(
              providerSelectionRequest,
              (err, selectionResponse) => {
                if (err || !selectionResponse.success) {
                  console.error("Failed to set the provider! Aborting...");
                  return;
                }
                console.log(`Successfully set the provider to: ${provider}`);

                // Prepare a request message with provider, model name, and prompt
                const chatRequest = {
                  client_id: clientId,
                  prompt,
                  provider,
                  model_name: modelName,
                };
                client.syncChat(chatRequest, (err, response) => {
                  if (err) return console.error("Error in syncChat:", err);
                  console.log("Response from syncChat:");
                  console.log(response.completion);

                  // Call the asyncChat method and get the response iterator
                  console.log("\nNow, let's test the asyncChat...");
                  const call = client.asyncChat(chatRequest);
                  call.on("data", (responsePart) => {
                    process.stdout.write(responsePart.token);
                  });
                  call.on("end", () => {
                    console.log("\nStreaming completed.");

                    // Test embedding capability if supported
                    if (providerCapabilities.capabilities.embedding) {
                      const embeddingRequest = {
                        client_id: clientId,
                        text: "Mickey Mouse is a Disney cartoon character.",
                      };
                      client.getEmbedding(
                        embeddingRequest,
                        (err, embeddingResponse) => {
                          if (err)
                            return console.error("Error in getEmbedding:", err);
                          console.log("Embedding returned:");
                          console.log(embeddingResponse.embedding.join(" "));

                          // Unregister the client
                          console.log("Unregistering the client...");
                          client.unregisterClient(
                            { client_id: clientId },
                            (err, unregistrationResponse) => {
                              if (
                                unregistrationResponse &&
                                unregistrationResponse.success
                              ) {
                                console.log(
                                  "Successfully unregistered the client."
                                );
                              } else {
                                console.error(
                                  "Failed to unregister the client."
                                );
                              }
                              client.close();
                            }
                          );
                        }
                      );
                    } else {
                      console.log(
                        `Provider ${provider} does not support the embedding capability.`
                      );
                    }
                  });
                  call.on("error", (err) => {
                    console.error("Error in asyncChat:", err);
                  });
                });
              }
            );
          }
        );
      });
    }
  );
}

// Parse command-line arguments
const args = require("minimist")(process.argv.slice(2));
const { host = "localhost", port = 50052, provider, model_name, prompt } = args;
if (!provider || !model_name || !prompt) {
  console.error(
    "Please specify --provider, --model_name, and --prompt arguments"
  );
  process.exit(1);
}

// Run the client with the provided arguments
runClient(host, port, provider, model_name, prompt);
