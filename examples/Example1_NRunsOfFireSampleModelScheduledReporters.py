########Demonstration of NL4Py#################
#This example takes two commandline arguments n and model
#n : number of concurrent model runs required.
#model: path to model file
#example usage: python NRunsOfAModel.py 100 netlogo_home
###############################################

print("\n\n------------ This is a Demonstration of NL4PY --------------------\n")

import nl4py 
import sys

print("\n1) Starting the NetLogoControllerServer with: nl4py.startServer()\n")

nl4py.startServer(sys.argv[2])



print('\n2) Starting the model runs... ')

try:
	n = sys.argv[1]
except:
	print("ERROR: Please provide the number of concurrent model runs required as a commandline argument.")

model = "./Fire.nlogo"

print('\n2.1) Creating ' + n + ' NetLogo HeadlessWorkspaces with: workspace = nl4py.newNetLogoHeadlessWorkspace()')
print('\n2.2) Opening ' + str(n) + ' copies of the model at ' + model + ' on the NetLogo HeadlessWorkspaces with: workspace.openModel("model")')
for i in range(0,int(n)):
	workspace = nl4py.netlogoWorkspaceFactory.newNetLogoHeadlessWorkspace()
	workspace.openModel(model)
	
print("\n2.3) Get all workspaces back with: workspaces = nl4py.getAllExistingWorkspaces() \n\tSetting the parameters for all " + str(n) + " models to random values with workspace.setParamsRandom()")
for workspace in nl4py.getAllHeadlessWorkspaces():
	workspace.setParamsRandom()

print('\n2.4) Send setup and go commands to each model using: workspace.command("setup") and workspace.command("go") ')
for workspace in nl4py.getAllHeadlessWorkspaces():
	workspace.command("setup")
	workspace.scheduleReportersAndRun(["ticks"],stopAtTick=5)

print('\n2.5) Get back current state from all executing models using a NetLogo reporter: workspace.report("burned-trees")')
for workspace in nl4py.getAllHeadlessWorkspaces():
	results = workspace.awaitScheduledReporterResults()
	print(results)

print('\n3) Shutdown the server to release compute resources using: nl4py.stopServer()')
nl4py.stopServer()
print('\n\n------------------------ Thanks for trying NL4PY -------------------------\n')