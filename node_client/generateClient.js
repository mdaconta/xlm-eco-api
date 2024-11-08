// Import necessary modules
const { exec } = require('child_process');
const path = require('path');

// Get the absolute path to the script's directory
const scriptDir = __dirname;

// Define the paths to the proto file and output directories
const protoDir = path.join(scriptDir, '../proto'); // Adjust to your proto files location
const generatedDir = path.join(scriptDir, 'generated');

// Ensure the output directory exists
const fs = require('fs');
if (!fs.existsSync(generatedDir)) {
    fs.mkdirSync(generatedDir, { recursive: true });
}

// Define the proto file path (you can adjust this to target a specific file or directory)
const protoFile = path.join(protoDir, 'xlm-eco-api.proto'); // Adjust this

// Construct the `protoc` command for Node.js client generation
const command = [
    `npx grpc_tools_node_protoc`,
    `--proto_path=${protoDir}`, // Specify proto path
    `--js_out=import_style=commonjs,binary:${generatedDir}`, // Output JS for use with Node
    `--grpc_out=grpc_js:${generatedDir}`, // Output gRPC code for Node.js
    protoFile // The proto file(s) to generate
].join(' ');

// Execute the command
exec(command, (error, stdout, stderr) => {
    if (error) {
        console.error(`Error executing protoc: ${error.message}`);
        return;
    }
    if (stderr) {
        console.error(`Protoc error output: ${stderr}`);
    }
    console.log(`Protoc output:\n${stdout}`);
    console.log("Node.js client generation complete. Files are in the 'generated' directory.");
});
